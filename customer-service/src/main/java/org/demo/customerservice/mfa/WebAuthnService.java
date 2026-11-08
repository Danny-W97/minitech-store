package org.demo.customerservice.mfa;

import org.demo.customerservice.audit.AuditService;
import org.demo.customerservice.auth.MfaChallengeExpiredException;
import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WebAuthnService {

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private final CustomerRepository customerRepository;
    private final CustomerWebAuthnUserEntityRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final Webauthn4JRelyingPartyOperations relyingParty;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final String rpId;

    public WebAuthnService(
            CustomerRepository customerRepository,
            CustomerWebAuthnUserEntityRepository userRepository,
            UserCredentialRepository credentialRepository,
            Webauthn4JRelyingPartyOperations relyingParty,
            StringRedisTemplate redis,
            AuditService auditService,
            @Value("${customer.webauthn.rp-id:localhost}") String rpId
    ) {
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.relyingParty = relyingParty;
        this.redis = redis;
        this.auditService = auditService;
        this.rpId = rpId;
    }

    public boolean hasCredentials(Long customerId) {
        return !credentialRepository.findByUserId(CustomerWebAuthnUserEntityRepository.userId(customerId)).isEmpty();
    }

    public int credentialCount(Long customerId) {
        return credentialRepository.findByUserId(CustomerWebAuthnUserEntityRepository.userId(customerId)).size();
    }

    @Transactional
    public WebAuthnResponses.RegistrationOptions registrationOptions(Long customerId) {
        Customer customer = customer(customerId);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                customer.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        PublicKeyCredentialCreationOptions options = relyingParty.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication)
        );
        redis.opsForValue().set(registrationKey(customerId), options.getChallenge().toBase64UrlString(), CHALLENGE_TTL);
        return toRegistrationOptions(options);
    }

    @Transactional
    public WebAuthnResponses.RegisteredCredential register(
            Long customerId,
            WebAuthnCredentialRequest request,
            String label
    ) {
        Customer customer = customer(customerId);
        String challenge = redis.opsForValue().getAndDelete(registrationKey(customerId));
        if (challenge == null) {
            throw new MfaChallengeExpiredException();
        }
        PublicKeyCredentialCreationOptions options = registrationOptionsFor(customer, Bytes.fromBase64(challenge));
        PublicKeyCredential<AuthenticatorAttestationResponse> credential = request.attestationCredential();
        var record = relyingParty.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                options, new RelyingPartyPublicKey(credential, safeLabel(label))
        ));
        auditService.recordForCustomer(customer.getId(),"MFA_WEBAUTHN_REGISTERED", customer.getEmail(), "MFA", "SUCCESS", "credential_id=" + record.getCredentialId().toBase64UrlString());
        return new WebAuthnResponses.RegisteredCredential(record.getCredentialId().toBase64UrlString(), safeLabel(label));
    }

    public String createLoginChallenge(Customer customer) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                customer.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        PublicKeyCredentialRequestOptions options = relyingParty.createCredentialRequestOptions(
                new ImmutablePublicKeyCredentialRequestOptionsRequest(authentication)
        );
        String challengeId = UUID.randomUUID().toString();
        redis.opsForValue().set(loginKey(challengeId), customer.getId() + "|" + options.getChallenge().toBase64UrlString(), CHALLENGE_TTL);
        return challengeId;
    }

    @Transactional
    public WebAuthnResponses.RequestOptions loginOptions(String challengeId) {
        Challenge challenge = challenge(challengeId);
        Customer customer = customer(challenge.customerId());
        PublicKeyCredentialRequestOptions options = requestOptionsFor(
                customer, Bytes.fromBase64(challenge.challenge())
        );
        return toRequestOptions(options);
    }

    @Transactional
    public Customer verifyLogin(String challengeId, WebAuthnCredentialRequest request) {
        Challenge challenge = readChallenge(challengeId, true);
        Customer customer = customer(challenge.customerId());
        PublicKeyCredentialRequestOptions options = requestOptionsFor(customer, Bytes.fromBase64(challenge.challenge()));
        PublicKeyCredential<AuthenticatorAssertionResponse> credential = request.assertionCredential();
        CustomerWebAuthnUserEntityRepository.userId(customer.getId());
        relyingParty.authenticate(new RelyingPartyAuthenticationRequest(options, credential));
        auditService.recordForCustomer(customer.getId(),"MFA_WEBAUTHN_LOGIN_SUCCESS", customer.getEmail(), "MFA", "SUCCESS", "");
        return customer;
    }

    private Customer customer(Long id) {
        return customerRepository.findByIdForUpdate(id).filter(Customer::isEnabled)
                .orElseThrow(MfaChallengeExpiredException::new);
    }

    private PublicKeyCredentialCreationOptions registrationOptionsFor(Customer customer, Bytes challenge) {
        return PublicKeyCredentialCreationOptions.builder()
                .rp(org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity.builder()
                        .id(rpId).name("MiniTech Store").build())
                .user(userRepository.findById(CustomerWebAuthnUserEntityRepository.userId(customer.getId())))
                .challenge(challenge)
                .pubKeyCredParams(List.of(
                        org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters.ES256,
                        org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters.RS256
                ))
                .timeout(CHALLENGE_TTL)
                .attestation(org.springframework.security.web.webauthn.api.AttestationConveyancePreference.NONE)
                .build();
    }

    private PublicKeyCredentialRequestOptions requestOptionsFor(Customer customer, Bytes challenge) {
        List<PublicKeyCredentialDescriptor> credentials = credentialRepository
                .findByUserId(CustomerWebAuthnUserEntityRepository.userId(customer.getId()))
                .stream()
                .map(record -> PublicKeyCredentialDescriptor.builder()
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .id(record.getCredentialId())
                        .transports(record.getTransports())
                        .build())
                .toList();
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(challenge)
                .timeout(CHALLENGE_TTL)
                .rpId(rpId)
                .allowCredentials(credentials)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build();
    }

    private WebAuthnResponses.RegistrationOptions toRegistrationOptions(PublicKeyCredentialCreationOptions options) {
        return new WebAuthnResponses.RegistrationOptions(
                options.getChallenge().toBase64UrlString(),
                options.getRp().getId(),
                options.getRp().getName(),
                new WebAuthnResponses.RegistrationOptions.User(
                        options.getUser().getId().toBase64UrlString(),
                        options.getUser().getName(),
                        options.getUser().getDisplayName()
                ),
                options.getTimeout().toMillis(),
                options.getPubKeyCredParams().stream().map(parameter -> parameter.getAlg().toString()).toList(),
                options.getExcludeCredentials().stream().map(descriptor -> descriptor.getId().toBase64UrlString()).toList(),
                options.getAuthenticatorSelection() == null || options.getAuthenticatorSelection().getUserVerification() == null
                        ? UserVerificationRequirement.PREFERRED.getValue()
                        : options.getAuthenticatorSelection().getUserVerification().getValue()
        );
    }

    private WebAuthnResponses.RequestOptions toRequestOptions(PublicKeyCredentialRequestOptions options) {
        return new WebAuthnResponses.RequestOptions(
                options.getChallenge().toBase64UrlString(),
                options.getTimeout().toMillis(),
                options.getRpId(),
                options.getAllowCredentials().stream().map(descriptor -> descriptor.getId().toBase64UrlString()).toList(),
                options.getUserVerification().getValue()
        );
    }

    private Challenge challenge(String challengeId) {
        return readChallenge(challengeId, false);
    }

    private Challenge readChallenge(String challengeId, boolean consume) {
        String raw = consume ? redis.opsForValue().getAndDelete(loginKey(challengeId))
                : redis.opsForValue().get(loginKey(challengeId));
        if (raw == null || !raw.contains("|")) {
            throw new MfaChallengeExpiredException();
        }
        String[] parts = raw.split("\\|", 2);
        return new Challenge(Long.valueOf(parts[0]), parts[1]);
    }

    private String registrationKey(Long customerId) {
        return "customer:mfa:webauthn:registration:" + customerId;
    }

    private String loginKey(String challengeId) {
        return "customer:mfa:webauthn:login:" + challengeId;
    }

    private String safeLabel(String label) {
        return label == null || label.isBlank() ? "Passkey" : label.trim().substring(0, Math.min(label.trim().length(), 255));
    }

    private record Challenge(Long customerId, String challenge) {
    }

    public record WebAuthnCredentialRequest(
            String id,
            String type,
            String rawId,
            ClientResponse response
    ) {
        public PublicKeyCredential<AuthenticatorAttestationResponse> attestationCredential() {
            return PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
                    .id(id)
                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                    .rawId(Bytes.fromBase64(rawId))
                    .response(AuthenticatorAttestationResponse.builder()
                            .clientDataJSON(Bytes.fromBase64(response.clientDataJSON()))
                            .attestationObject(Bytes.fromBase64(response.attestationObject()))
                            .build())
                    .build();
        }

        public PublicKeyCredential<AuthenticatorAssertionResponse> assertionCredential() {
            return PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
                    .id(id)
                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                    .rawId(Bytes.fromBase64(rawId))
                    .response(AuthenticatorAssertionResponse.builder()
                            .clientDataJSON(Bytes.fromBase64(response.clientDataJSON()))
                            .authenticatorData(Bytes.fromBase64(response.authenticatorData()))
                            .signature(Bytes.fromBase64(response.signature()))
                            .userHandle(response.userHandle() == null || response.userHandle().isBlank()
                                    ? null : Bytes.fromBase64(response.userHandle()))
                            .build())
                    .build();
        }
    }

    public record ClientResponse(
            String clientDataJSON,
            String attestationObject,
            String authenticatorData,
            String signature,
            String userHandle
    ) {
    }
}
