package org.demo.customerservice.web;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.customerservice.auth.AuthService;
import org.demo.customerservice.auth.AuthSession;
import org.demo.customerservice.auth.AuthLoginResult;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.auth.InvalidRefreshTokenException;
import org.demo.customerservice.auth.AuthResponse;
import org.demo.customerservice.customer.CustomerAccessDeniedException;
import org.demo.customerservice.customer.CustomerService;
import org.demo.customerservice.mfa.MfaService;
import org.demo.customerservice.mfa.MfaResponses;
import org.demo.customerservice.mfa.WebAuthnService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final AuthService authService;
    private final MfaService mfaService;
    private final WebAuthnService webAuthnService;
    private final String refreshCookieName;
    private final String refreshCookiePath;
    private final boolean refreshCookieSecure;
    private final long refreshTokenTtlSeconds;

    public CustomerController(
            CustomerService customerService,
            AuthService authService,
            MfaService mfaService,
            WebAuthnService webAuthnService,
            @Value("${customer.auth.refresh-cookie-name:MINITECH_REFRESH_TOKEN}") String refreshCookieName,
            @Value("${customer.auth.refresh-cookie-path:/api/customers}") String refreshCookiePath,
            @Value("${customer.auth.refresh-cookie-secure:false}") boolean refreshCookieSecure,
            @Value("${customer.auth.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds
    ) {
        this.customerService = customerService;
        this.authService = authService;
        this.mfaService = mfaService;
        this.webAuthnService = webAuthnService;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookiePath = refreshCookiePath;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody CustomerRequests.Register request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.register(request));
    }

    @GetMapping("/verify")
    public CustomerResponse verify(@RequestParam String token) {
        return customerService.verify(token);
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return authService.jwks();
    }

    @PostMapping("/verification/resend")
    public ResponseEntity<VerificationResendResponse> resendVerification(
            @Valid @RequestBody CustomerRequests.ResendVerification request
    ) {
        customerService.resendVerification(request);
        return ResponseEntity.accepted().body(new VerificationResendResponse(
                "If an unconfirmed account exists for this address, a new confirmation link may have been sent. Check your mailbox."
        ));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<VerificationResendResponse> requestPasswordReset(
            @Valid @RequestBody CustomerRequests.PasswordResetRequest request
    ) {
        customerService.requestPasswordReset(request);
        return ResponseEntity.accepted().body(new VerificationResendResponse(
                "If an active account exists for this address, a password reset link has been sent."
        ));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<VerificationResendResponse> confirmPasswordReset(
            @Valid @RequestBody CustomerRequests.PasswordResetConfirm request
    ) {
        customerService.confirmPasswordReset(request);
        return ResponseEntity.ok(new VerificationResendResponse("Password changed successfully."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody CustomerRequests.Login request) {
        AuthLoginResult result = authService.login(request);
        if (result.requiresMfa()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.challenge());
        }
        return sessionResponse(result.session());
    }

    @PostMapping("/mfa/login/verify")
    public ResponseEntity<AuthResponse> verifyMfaLogin(@Valid @RequestBody MfaRequests.LoginCode request) {
        return sessionResponse(authService.completeMfaLogin(request));
    }

    @GetMapping("/mfa/webauthn/login/options")
    public Object webAuthnLoginOptions(@RequestParam String challengeId) {
        return webAuthnService.loginOptions(challengeId);
    }

    @PostMapping("/mfa/webauthn/login/verify")
    public ResponseEntity<AuthResponse> verifyWebAuthnLogin(
            @Valid @RequestBody MfaRequests.WebAuthnLogin request
    ) {
        return sessionResponse(authService.completeWebAuthnLogin(request));
    }

    @GetMapping("/mfa/status")
    @SecurityRequirement(name = "bearerAuth")
    public MfaResponses.Status mfaStatus(@AuthenticationPrincipal CustomerPrincipal principal) {
        return mfaService.status(
                principal.customerId(), webAuthnService.hasCredentials(principal.customerId()),
                webAuthnService.credentialCount(principal.customerId())
        );
    }

    @PostMapping("/mfa/totp/enroll")
    @SecurityRequirement(name = "bearerAuth")
    public MfaResponses.TotpEnrollment beginTotpEnrollment(
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        return mfaService.beginTotpEnrollment(principal.customerId(), principal.email());
    }

    @PostMapping("/mfa/totp/confirm")
    @SecurityRequirement(name = "bearerAuth")
    public MfaResponses.RecoveryCodes confirmTotpEnrollment(
            @Valid @RequestBody MfaRequests.Code request,
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        return mfaService.confirmTotpEnrollment(principal.customerId(), principal.email(), request.code());
    }

    @PostMapping("/mfa/totp/disable")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> disableTotp(
            @Valid @RequestBody MfaRequests.Code request,
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        mfaService.disableTotp(principal.customerId(), principal.email(), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/recovery-codes/regenerate")
    @SecurityRequirement(name = "bearerAuth")
    public MfaResponses.RecoveryCodes regenerateRecoveryCodes(
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        return mfaService.regenerateRecoveryCodes(principal.customerId());
    }

    @GetMapping("/mfa/webauthn/register/options")
    @SecurityRequirement(name = "bearerAuth")
    public Object webAuthnRegistrationOptions(@AuthenticationPrincipal CustomerPrincipal principal) {
        return webAuthnService.registrationOptions(principal.customerId());
    }

    @PostMapping("/mfa/webauthn/register")
    @SecurityRequirement(name = "bearerAuth")
    public Object registerWebAuthn(
            @Valid @RequestBody WebAuthnService.WebAuthnCredentialRequest request,
            @RequestParam(defaultValue = "Passkey") String label,
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        return webAuthnService.register(principal.customerId(), request, label);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            HttpServletRequest request
    ) {
        return sessionResponse(authService.refresh(refreshCookieValue(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request
    ) {
        authService.logout(refreshCookieValue(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .build();
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public CustomerResponse me(@AuthenticationPrincipal CustomerPrincipal principal) {
        return customerService.get(principal.customerId());
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public CustomerResponse get(@PathVariable Long id, @AuthenticationPrincipal CustomerPrincipal principal) {
        assertOwner(id, principal);
        return customerService.get(id);
    }

    @PutMapping("/{id}/address")
    @SecurityRequirement(name = "bearerAuth")
    public CustomerResponse updateAddress(
            @PathVariable Long id,
            @Valid @RequestBody CustomerRequests.Address request,
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        assertOwner(id, principal);
        return customerService.updateAddress(id, request);
    }

    @PutMapping("/{id}/profile")
    @SecurityRequirement(name = "bearerAuth")
    public CustomerResponse updateProfile(
            @PathVariable Long id,
            @Valid @RequestBody CustomerRequests.Profile request,
            @AuthenticationPrincipal CustomerPrincipal principal
    ) {
        assertOwner(id, principal);
        return customerService.updateProfile(id, request);
    }

    private ResponseEntity<AuthResponse> sessionResponse(AuthSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.rawRefreshToken()).toString())
                .body(session.response());
    }

    private ResponseCookie refreshCookie(String rawValue) {
        return ResponseCookie.from(refreshCookieName, rawValue)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .path(refreshCookiePath)
                .maxAge(Duration.ofSeconds(refreshTokenTtlSeconds))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .path(refreshCookiePath)
                .maxAge(Duration.ZERO)
                .build();
    }

    private String refreshCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (refreshCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void assertOwner(Long id, CustomerPrincipal principal) {
        if (principal == null || !id.equals(principal.customerId())) {
            throw new CustomerAccessDeniedException();
        }
    }
}
