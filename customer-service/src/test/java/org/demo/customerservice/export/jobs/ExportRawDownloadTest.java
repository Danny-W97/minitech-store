package org.demo.customerservice.export.jobs;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportRawDownloadTest {
    @Test void rawDownloadKeepsExactJsonBytesAndStringIdsWithoutChangingDefaultContract(){
        var store=mock(ExportJobStore.class);var policy=mock(ExportPolicy.class);var json=new ExportJson();
        var owner=new CustomerPrincipal(1L,"owner@fixture.example");var id=UUID.randomUUID();var now=Instant.now();var hash="a".repeat(64);
        String raw="{\"amount\":123456789.123456789,\"orderId\":9007199254740993,\"name\":\"Żółć\"}";
        var job=new ExportJobStore.Job(id,1L,"READY",now,now.plusSeconds(3600),now,hash,json.bytes(raw),1,null,null,false,false);
        when(policy.hash()).thenReturn(hash);when(store.get(id,owner)).thenReturn(job);
        when(store.counts(id)).thenReturn(new ExportJobStore.Counts(1,0,0));
        when(store.parts(id,owner,0,3)).thenReturn(List.of(new ExportJobStore.Part(1,"PAYMENT",9007199254740993L,0,0,raw,json.bytes(raw),org.demo.platformsecurity.Access.hash(raw),now,now)));
        var coordinator=new ExportCoordinator(store,null,null,policy,json);
        var result=coordinator.chunks(id,owner,0,3,true);
        assertThat(result.get("ownerId")).isEqualTo("1");
        var body=json.tree(result).path("parts").get(0);
        assertThat(body.path("dataJson").asText()).isEqualTo(raw);
        assertThat(body.path("parentId").asText()).isEqualTo("9007199254740993");assertThat(body.has("data")).isFalse();
        assertThat(json.tree(coordinator.chunks(id,owner,0,3)).path("parts").get(0).path("data").path("orderId").longValue()).isEqualTo(9007199254740993L);
        assertThat(coordinator.status(id,owner).get("ownerId")).isEqualTo("1");
    }
}
