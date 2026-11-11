package org.demo.orderservice.privacy;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationProgress.*;
import static org.demo.orderservice.privacy.CommerceRetentionHoldRestorationReview.*;

class HoldReviewFingerprintTest {
    public record First(String value){}
    public record Second(String value){}
    @Test void mapsAndSetsAreStableWithoutDependingOnInsertionOrder(){
        var a=new LinkedHashMap<String,Object>();a.put("z",List.of(1,2));a.put("a",null);
        var b=new LinkedHashMap<String,Object>();b.put("a",null);b.put("z",List.of(1L,2L));
        assertThat(HoldReviewFingerprint.hash("domain",a,new LinkedHashSet<>(List.of("b","a")))).isEqualTo(HoldReviewFingerprint.hash("domain",b,new LinkedHashSet<>(List.of("a","b"))));
    }
    @Test void domainTypesNullAndLengthPrefixesPreventAmbiguousConcatenation(){
        assertThat(HoldReviewFingerprint.hash("a","bc")).isNotEqualTo(HoldReviewFingerprint.hash("ab","c"));
        assertThat(HoldReviewFingerprint.hash("a",List.of("ab","c"))).isNotEqualTo(HoldReviewFingerprint.hash("a",List.of("a","bc")));
        assertThat(HoldReviewFingerprint.hash("a",(Object)null)).isNotEqualTo(HoldReviewFingerprint.hash("a","null"));
        assertThat(HoldReviewFingerprint.hash("a",true)).isNotEqualTo(HoldReviewFingerprint.hash("a","true"));
    }
    @Test void timestampsAndIntegerJdbcTypesHaveStableNormalizedRepresentations(){
        Instant now=Instant.parse("2026-11-02T00:00:00.123456Z");
        assertThat(HoldReviewFingerprint.hash("a",now,(short)5)).isEqualTo(HoldReviewFingerprint.hash("a",Timestamp.from(now),5L));
    }
    @Test void recordTypeAndFieldChangesHaveDistinctDigests(){assertThat(HoldReviewFingerprint.hash("a",new First("value"))).isNotEqualTo(HoldReviewFingerprint.hash("a",new Second("value")));assertThat(HoldReviewFingerprint.hash("a",new First("value"))).isNotEqualTo(HoldReviewFingerprint.hash("a",new First("other")));}
    @Test void unsupportedMetadataFailsWithoutEchoingItsValue(){var sensitive=new Object(){public String toString(){return "fixture-sensitive";}};assertThatThrownBy(()->HoldReviewFingerprint.hash("a",sensitive)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid review fingerprint metadata").hasNoCause();}
    private static Review view(Inspection inspection,Instant observed){
        var p=new Progress(CommerceRetentionHoldRestorationProgress.Code.ASSESSED,Delivery.HISTORICAL_ACKNOWLEDGEMENT,1,0,1,2,6,0,0,inspection,UUID.randomUUID(),observed,2,4);
        var i=new Intent(UUID.randomUUID(),UUID.randomUUID(),1,false,p);
        var s=new Source(org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant.PAYMENT,UUID.randomUUID(),UUID.randomUUID(),2,6,0,0,p.latestProbeId(),List.of(i),Set.of());
        return new Review(CommerceRetentionHoldRestorationReview.Code.REVIEWED,observed,List.of(s),new Totals(1,1,1,0,1,2,0,0,0,1,0));
    }
    @Test void leaseIsBoundedByEarliestFreshSourceObservationAndMaximumFifteenSeconds(){
        Instant now=Instant.parse("2026-11-02T00:00:00Z");
        assertThat(CommerceRetentionHoldReviewVersions.expiry(view(Inspection.MATCH_AS_OF_READ,now.minusSeconds(14)),now)).isEqualTo(now.plusSeconds(1));
        assertThat(CommerceRetentionHoldReviewVersions.expiry(view(Inspection.MATCH_AS_OF_READ,now.plusSeconds(5)),now)).isEqualTo(now.plusSeconds(15));
        assertThat(CommerceRetentionHoldReviewVersions.expiry(view(Inspection.STALE,now.minusSeconds(60)),now)).isEqualTo(now.plusSeconds(15));
    }
    @Test void clockRollbackAndExactExpiryAreRejectedWithoutClientClockInput(){
        Instant now=Instant.parse("2026-11-02T00:00:00Z");var s=new CommerceRetentionHoldReviewVersions.Snapshot(UUID.randomUUID(),1,CommerceRetentionHoldReviewVersions.PROTOCOL,"a","b","c","d",now,now.plusSeconds(15));
        assertThat(CommerceRetentionHoldReviewVersions.clock(s,now)).isNull();
        assertThat(CommerceRetentionHoldReviewVersions.clock(s,now.plusSeconds(15))).isEqualTo(CommerceRetentionHoldReviewVersions.Code.EXPIRED);
        assertThat(CommerceRetentionHoldReviewVersions.clock(s,now.minusNanos(1))).isEqualTo(CommerceRetentionHoldReviewVersions.Code.CLOCK_CHANGED);
    }
}
