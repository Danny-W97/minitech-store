package org.demo.platformsecurity.privacy;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommerceRetentionPreservationReviewTest {
    byte[] resource() throws Exception {
        try (var in = getClass().getResourceAsStream(CommerceRetentionPreservationReview.RESOURCE)) {
            return in.readAllBytes();
        }
    }

    @Test void packagedPreservationReviewHasItsReviewedDigest() throws Exception {
        assertThat(CommerceRetentionPreservationReview.verifiedDigest()).isEqualTo(CommerceRetentionPreservationReview.SHA256);
        assertThat(CommerceRetentionPreservationReview.verify(resource())).isEqualTo(CommerceRetentionPreservationReview.SHA256);
    }

    @Test void windowsCheckoutLineEndingsDoNotChangeThePreservationReview() throws Exception {
        assertThat(CommerceRetentionPreservationReview.verify(
                new String(resource(), StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(CommerceRetentionPreservationReview.SHA256);
    }

    @Test void alteredPolicyDecisionsAreRejectedWithoutEchoingInput() throws Exception {
        var original = new String(resource(), StandardCharsets.UTF_8);
        for (var change : List.of(
                original.replace("365", "364"),
                original.replace("all-completed-reconciled-purchases", "unreviewed-purchases"),
                original.replace("PRESERVATION_POLICY_REVIEWED", "UNREVIEWED"),
                original + "tampered-content")) {
            assertThatThrownBy(() -> CommerceRetentionPreservationReview.verify(change.getBytes(StandardCharsets.UTF_8)))
                    .hasMessage("RETENTION_PRESERVATION_POLICY_UNAVAILABLE");
        }
    }

    @Test void missingEmptyOversizedAndMalformedReviewsFailClosed() {
        for (byte[] raw : Arrays.asList(null, new byte[0], new byte[65537], new byte[]{(byte) 0xff}, "{}".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> CommerceRetentionPreservationReview.verify(raw))
                    .hasMessage("RETENTION_PRESERVATION_POLICY_UNAVAILABLE");
        }
    }
}
