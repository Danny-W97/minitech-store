package org.demo.platformevents;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.*;
import java.util.*;
import java.util.function.Consumer;

/** Redis 7-compatible recovery; SQL handlers must commit before the ACK. */
public final class RedisEventStreams {
    private final StringRedisTemplate redis;
    private final String stream,group,name;
    private final long idleMillis,retentionMillis;
    private final List<String> requiredGroups;
    private String cursor="0-0";
    private long nextTrim;
    public RedisEventStreams(StringRedisTemplate redis,String stream,String group,String prefix,long idleMillis,long retentionMillis,String requiredGroups) {
        if(idleMillis<1000 || retentionMillis<1000 || prefix.length()>80) throw new IllegalArgumentException("Invalid stream recovery settings");
        this.redis=redis;this.stream=stream;this.group=group;name=prefix+"-"+UUID.randomUUID();
        this.idleMillis=idleMillis;this.retentionMillis=retentionMillis;
        this.requiredGroups=Arrays.stream(requiredGroups.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    public String consumerName() { return name; }
    private static final DefaultRedisScript<Long> ENSURE=new DefaultRedisScript<>("""
        local result=redis.pcall('XGROUP','CREATE',KEYS[1],ARGV[1],'0-0','MKSTREAM')
        if type(result)=='table' and result.err then
          if string.find(result.err,'BUSYGROUP',1,true) then return 0 end
          return redis.error_reply(result.err)
        end
        return 1
        """,Long.class);
    private static final DefaultRedisScript<List> CLAIM=new DefaultRedisScript<>(
        "return redis.call('XAUTOCLAIM',KEYS[1],ARGV[1],ARGV[2],ARGV[3],ARGV[4],'COUNT',20)",List.class);
    public synchronized void poll(Consumer<MapRecord<String,?,?>> handler) {
        redis.execute(ENSURE,List.of(stream),group);
        var claimed=redis.execute(CLAIM,List.of(stream),group,name,Long.toString(idleMillis),cursor);
        if(claimed!=null && claimed.size()>=2) {
            cursor=claimed.get(0).toString();
            if(claimed.size()>2 && !((List<?>)claimed.get(2)).isEmpty())
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Pending entries were deleted outside safe retention: {} {}",stream,claimed.get(2));
            for(Object entry:(List<?>)claimed.get(1)) {
                var row=(List<?>)entry; var pairs=(List<?>)row.get(1); var values=new LinkedHashMap<String,String>();
                for(int i=0;i<pairs.size();i+=2) values.put(pairs.get(i).toString(),pairs.get(i+1).toString());
                deliver(StreamRecords.newRecord().ofMap(values).withStreamKey(stream).withId(RecordId.of(row.get(0).toString())),handler);
            }
        }
        var records=redis.opsForStream().read(org.springframework.data.redis.connection.stream.Consumer.from(group,name),
                StreamReadOptions.empty().count(20),StreamOffset.create(stream,ReadOffset.lastConsumed()));
        if(records!=null) records.forEach(record -> deliver(record,handler));
        if(System.currentTimeMillis()>=nextTrim) { trim(); nextTrim=System.currentTimeMillis()+60000; }
    }
    private void deliver(MapRecord<String,?,?> record,Consumer<MapRecord<String,?,?>> handler) {
        Object candidate=record.getValue().get("correlationId");
        try(var correlation=org.demo.platformobservability.CorrelationIdContext.open(candidate instanceof String id?id:null)) {
            handler.accept(record);
        }
    }
    public void acknowledge(RecordId id) {
        redis.opsForStream().acknowledge(stream,group,id);
        new RedisPrivacyStreams(redis).afterAcknowledgement(stream,String.join(",",requiredGroups),id.getValue());
    }
    // One atomic snapshot prevents a read/trim race. Never trim an unread or pending entry.
    private static final DefaultRedisScript<Long> TRIM=new DefaultRedisScript<>("""
        local function less(a,b)
          local am,as=string.match(a,'^(%d+)%-(%d+)$'); local bm,bs=string.match(b,'^(%d+)%-(%d+)$')
          if #am~=#bm then return #am<#bm end
          if am~=bm then return am<bm end
          if #as~=#bs then return #as<#bs end
          return as<bs
        end
        local groups=redis.call('XINFO','GROUPS',KEYS[1]); local seen={}; local cutoff=ARGV[1]
        for _,raw in ipairs(groups) do
          local g={};for i=1,#raw,2 do g[raw[i]]=raw[i+1] end
          seen[g.name]=true
          if less(g['last-delivered-id'],cutoff) then cutoff=g['last-delivered-id'] end
          local pending=redis.call('XPENDING',KEYS[1],g.name)
          if pending[1]>0 and less(pending[2],cutoff) then cutoff=pending[2] end
        end
        for i=2,#ARGV do if not seen[ARGV[i]] then return 0 end end
        return redis.call('XTRIM',KEYS[1],'MINID','~',cutoff,'LIMIT',1000)
        """,Long.class);
    public long trim() {
        if(requiredGroups.isEmpty()) return 0; // No declared recipients: require an explicit retention decision.
        var arguments=new ArrayList<String>();arguments.add(Math.max(0,System.currentTimeMillis()-retentionMillis)+"-0");arguments.addAll(requiredGroups);
        Long removed=redis.execute(TRIM,List.of(stream),arguments.toArray()); return removed==null ? 0 : removed;
    }
}
