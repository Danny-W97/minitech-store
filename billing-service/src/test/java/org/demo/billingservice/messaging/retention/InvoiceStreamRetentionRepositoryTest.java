package org.demo.billingservice.messaging.retention;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InvoiceStreamRetentionRepositoryTest {
    @Test void invalidNamesAndBudgetsNeverContactRedis() {
        var redis=mock(StringRedisTemplate.class);
        for(String key:new String[]{"","  ","bad\nkey","x".repeat(201)})assertThatThrownBy(()->new InvoiceStreamRetentionRepository(redis,key)).isInstanceOf(IllegalArgumentException.class);
        var repo=new InvoiceStreamRetentionRepository(redis,"fixture");
        assertThatThrownBy(()->repo.run(-1,true,1,List.of(),false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repo.run(1,true,1001,List.of(),false)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(redis);
    }
    @Test void malformedRepliesAreRejectedWithoutEchoingTheirContents() {
        var redis=mock(StringRedisTemplate.class);var repo=new InvoiceStreamRetentionRepository(redis,"fixture");
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class)))
                .thenReturn(List.of(0L,1L,"fixture-secret",0L,0L,0L,0L));
        assertThatThrownBy(()->repo.run(1,false,2,List.of(),false)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("fixture-secret");
    }
    @Test void futureUnsignedTimestampHasZeroAgeWithoutNumericPrecisionLoss() {
        var future=new InvoiceStreamRetentionRepository.Snapshot(0,1,"18446744073709551615-18446744073709551615",false,0,0,false);
        assertThat(future.oldestAgeSeconds(1_800_000_000_000L)).isZero();
    }
}
