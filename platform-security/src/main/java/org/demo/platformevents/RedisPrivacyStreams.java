package org.demo.platformevents;

import org.demo.platformsecurity.privacy.FinancePreparationAdapter.Result;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;

/** Standalone Redis 7. SQL ownership must be established before installing a fence. */
public final class RedisPrivacyStreams {
    private final StringRedisTemplate redis;
    public RedisPrivacyStreams(StringRedisTemplate redis){this.redis=redis;}
    private static String key(String stream,long order){return "privacy:stream:"+stream+":order:"+order;}
    private static final String LESS="""
        local function less(a,b)
          local am,as=string.match(a,'^(%d+)%-(%d+)$');local bm,bs=string.match(b,'^(%d+)%-(%d+)$')
          if #am~=#bm then return #am<#bm end;if am~=bm then return am<bm end
          if #as~=#bs then return #as<#bs end;return as<bs
        end
        """;
    private static final DefaultRedisScript<String> PUBLISH=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[2])==1 then
          local allowed={eventId=true,eventType=true,invoiceId=true,orderId=true,invoiceNumber=true,grossAmount=true,currency=true,createdAt=true,
             correctionId=true,correctionNumber=true,originalInvoiceNumber=true,correctionGross=true,finalGross=true,balanceModel=true,balanceVersion=true,privacySuppressed=true}
          local safe=false
          for i=1,#ARGV,2 do if ARGV[i]=='privacySuppressed' and ARGV[i+1]=='true' then safe=true end end
          if not safe then return 'PRIVACY_SUPPRESSED' end
          for i=1,#ARGV,2 do if not allowed[ARGV[i]] then return redis.error_reply('Private fields after privacy fence') end end
        end
        return redis.call('XADD',KEYS[1],'*',unpack(ARGV))
        """,String.class);
    public static boolean publish(StringRedisTemplate redis,String stream,long order,Map<String,String> fields){
        var args=new ArrayList<String>();fields.forEach((name,value)->{args.add(name);args.add(Objects.requireNonNull(value));});
        return redis.execute(PUBLISH,List.of(stream,key(stream,order)),args.toArray())!=null;
    }
    private static final DefaultRedisScript<String> FENCE=new DefaultRedisScript<>("""
        local previous=redis.call('GET',KEYS[2])
        if previous then
          if string.sub(previous,1,#ARGV[1]+1)~=ARGV[1]..'|' then return redis.error_reply('Privacy stream identity conflict') end
          return string.sub(previous,#ARGV[1]+2)
        end
        local last=redis.call('XREVRANGE',KEYS[1],'+','-','COUNT',1);local upper='0-0'
        if #last>0 then upper=last[1][1] end
        redis.call('SET',KEYS[2],ARGV[1]..'|'..upper);return upper
        """,String.class);
    private static final DefaultRedisScript<List> RANGE=new DefaultRedisScript<>("return redis.call('XRANGE',KEYS[1],'('..ARGV[1],ARGV[2],'COUNT',100)",List.class);
    private static final DefaultRedisScript<Long> PRUNE=new DefaultRedisScript<>(LESS+"""
        if redis.call('EXISTS',KEYS[2])==0 then return -4 end
        local rows=redis.call('XRANGE',KEYS[1],ARGV[1],ARGV[1]);if #rows==0 then return 0 end
        local own=false;local safe=false
        for i=1,#rows[1][2],2 do
          if rows[1][2][i]=='orderId' and rows[1][2][i+1]==ARGV[2] then own=true end
          if rows[1][2][i]=='privacySuppressed' and rows[1][2][i+1]=='true' then safe=true end
        end
        if not own then return -4 end;if safe then return 2 end
        if #ARGV<3 then return -1 end
        local groups=redis.call('XINFO','GROUPS',KEYS[1]);local seen={}
        for _,raw in ipairs(groups) do
          local g={};for i=1,#raw,2 do g[raw[i]]=raw[i+1] end;seen[g.name]=true
          if less(g['last-delivered-id'],ARGV[1]) then return -3 end
          local pending=redis.call('XPENDING',KEYS[1],g.name,ARGV[1],ARGV[1],1)
          if #pending>0 then return -3 end
        end
        for i=3,#ARGV do if not seen[ARGV[i]] then return -2 end end
        return redis.call('XDEL',KEYS[1],ARGV[1])
        """,Long.class);
    public Result prepare(String stream,String groups,long order,UUID request,String cursor){
        String upper=redis.execute(FENCE,List.of(stream,key(stream,order)),request.toString());
        if(upper==null)throw new IllegalStateException("Privacy stream fence unavailable");
        if(upper.equals("0-0"))return Result.prepared();
        var rows=redis.execute(RANGE,List.of(stream),cursor,upper);if(rows==null)throw new IllegalStateException("Privacy stream scan unavailable");
        String previous=cursor;
        for(Object raw:rows){var row=(List<?>)raw;String id=row.get(0).toString();var fields=(List<?>)row.get(1);boolean own=false;
            for(int n=0;n<fields.size();n+=2)if(fields.get(n).toString().equals("orderId")&&fields.get(n+1).toString().equals(Long.toString(order)))own=true;
            if(own){long removed=prune(stream,groups,order,id);if(removed<0)return Result.waitFor(removed==-1||removed==-2?"WAITING_STREAM_POLICY":"WAITING_STREAM_ACK",previous);}
            previous=id;
        }
        return rows.size()<100||previous.equals(upper)?Result.prepared():Result.more(previous);
    }
    private long prune(String stream,String groups,long order,String id){
        var args=new ArrayList<String>(List.of(id,Long.toString(order)));Arrays.stream(groups.split(",")).map(String::trim).filter(s->!s.isEmpty()).forEach(args::add);
        Long result=redis.execute(PRUNE,List.of(stream,key(stream,order)),args.toArray());if(result==null)throw new IllegalStateException("Privacy stream pruning unavailable");return result;
    }
    /** Called only after the inbox commit and ACK; also removes late v1 copies. */
    public void afterAcknowledgement(String stream,String groups,String id){
        var rows=redis.execute(RANGE,List.of(stream),previous(id),id);if(rows==null||rows.isEmpty())return;
        var pairs=(List<?>)((List<?>)rows.getFirst()).get(1);
        for(int n=0;n<pairs.size();n+=2)if(pairs.get(n).toString().equals("orderId")){
            String raw=pairs.get(n+1).toString();if(raw.matches("[1-9][0-9]{0,18}")){
                long order=Long.parseLong(raw);if(Boolean.TRUE.equals(redis.hasKey(key(stream,order))))prune(stream,groups,order,id);
            }return;
        }
    }
    private static String previous(String id){var split=id.split("-");var seq=new java.math.BigInteger(split[1]);return seq.signum()>0?split[0]+"-"+seq.subtract(java.math.BigInteger.ONE):new java.math.BigInteger(split[0]).subtract(java.math.BigInteger.ONE)+"-18446744073709551615";}
}
