package org.demo.billingservice.messaging.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.math.BigInteger;

@Repository
public class InvoiceStreamRetentionRepository {
    public static final int READY=0, MISSING=1, REQUIRED_GROUP_MISSING=2, NO_GROUP_POLICY=3, TOO_MANY_GROUPS=4, PROTECTED_FRONTIER=5;
    private static final DefaultRedisScript<List> SCRIPT=new DefaultRedisScript<>();
    static {SCRIPT.setLocation(new ClassPathResource("redis/invoice-stream-retention.lua"));SCRIPT.setResultType(List.class);}
    public record Snapshot(long removed,long length,String oldestId,boolean eligible,int blocked,long groups,boolean old) {
        public long oldestAgeSeconds(long nowMillis) {
            if(oldestId.isEmpty()) return 0;
            var millis=new BigInteger(oldestId.substring(0,oldestId.indexOf('-')));
            var age=BigInteger.valueOf(nowMillis).subtract(millis).max(BigInteger.ZERO).divide(BigInteger.valueOf(1000));
            return age.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        }
    }
    private final StringRedisTemplate redis;
    private final String stream;
    public InvoiceStreamRetentionRepository(StringRedisTemplate redis,@Value("${messaging.invoice-stream:invoice-events}") String stream) {
        if(stream==null || stream.isBlank() || stream.length()>200 || stream.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid invoice stream name");
        this.redis=redis;this.stream=stream;
    }
    public Snapshot run(long cutoffMillis,boolean deleting,int batchSize,List<String> groups,boolean allowNoGroups) {
        if(cutoffMillis<0 || batchSize<1 || batchSize>1000 || groups==null || groups.size()>32)
            throw new IllegalArgumentException("Invalid invoice stream retention batch");
        var args=new ArrayList<String>();args.add(cutoffMillis+"-0");args.add(deleting?"1":"0");args.add(allowNoGroups?"1":"0");args.add(Integer.toString(batchSize));args.addAll(groups);
        List<?> result=redis.execute(SCRIPT,List.of(stream),args.toArray());
        if(result==null || result.size()!=7 || !(result.get(2) instanceof String oldest)
                || (!oldest.isEmpty() && !oldest.matches("[0-9]{1,20}-[0-9]{1,20}"))) throw new IllegalStateException("Invalid invoice stream retention response");
        long removed=number(result,0),length=number(result,1),eligible=number(result,3),blocked=number(result,4),count=number(result,5),old=number(result,6);
        if(removed>batchSize || eligible>1 || blocked>5 || old>1) throw new IllegalStateException("Invalid invoice stream retention counters");
        return new Snapshot(removed,length,oldest,eligible==1,(int)blocked,count,old==1);
    }
    private static long number(List<?> values,int index) {
        if(!(values.get(index) instanceof Long value) || value<0) throw new IllegalStateException("Invalid invoice stream retention counter");
        return value;
    }
}
