package org.demo.customerservice.mfa;

import org.demo.customerservice.audit.AuditService;
import org.demo.customerservice.auth.MfaChallengeExpiredException;
import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebAuthnChallengeTest {
    @Test @SuppressWarnings("unchecked") void successfulAssertionConsumesChallengeBeforeVerificationAndCannotBeReplayed() {
        var customers = mock(CustomerRepository.class);
        var user = mock(Customer.class);
        when(user.getId()).thenReturn(1L); when(user.isEnabled()).thenReturn(true);
        when(customers.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var pending = new AtomicReference<>("1|YQ");
        when(values.getAndDelete(anyString())).thenAnswer(call -> pending.getAndSet(null));
        var relyingParty = mock(Webauthn4JRelyingPartyOperations.class);
        var service = new WebAuthnService(customers, mock(CustomerWebAuthnUserEntityRepository.class),
                mock(UserCredentialRepository.class), relyingParty, redis, mock(AuditService.class), "localhost");
        var request = new WebAuthnService.WebAuthnCredentialRequest("YQ", "public-key", "YQ",
                new WebAuthnService.ClientResponse("e30", null, "YQ", "YQ", null));
        assertThat(service.verifyLogin("challenge", request)).isSameAs(user);
        assertThatThrownBy(() -> service.verifyLogin("challenge", request)).isInstanceOf(MfaChallengeExpiredException.class);
        verify(relyingParty, times(1)).authenticate(any());
        verify(values, never()).get(anyString());
        verify(redis, never()).delete(anyString());
    }
}
