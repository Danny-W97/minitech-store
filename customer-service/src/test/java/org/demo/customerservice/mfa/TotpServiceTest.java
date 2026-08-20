package org.demo.customerservice.mfa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {

    private final TotpService service = new TotpService();

    @Test
    void followsRfc6238Sha1VectorForSixDigits() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertEquals("287082", service.codeForStep(secret, 1));
        assertTrue(service.verify(secret, "287082", 59, null).valid());
    }

    @Test
    void rejectsReplayOfAlreadyUsedTimeStep() {
        String secret = service.generateSecret();
        String code = service.codeForStep(secret, 100);

        assertTrue(service.verify(secret, code, 100 * 30L, null).valid());
        assertFalse(service.verify(secret, code, 100 * 30L, 100L).valid());
    }
}
