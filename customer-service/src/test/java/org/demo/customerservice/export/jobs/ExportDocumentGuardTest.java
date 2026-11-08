package org.demo.customerservice.export.jobs;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExportDocumentGuardTest {
    ExportJson json=new ExportJson();ExportDocumentGuard guard=new ExportDocumentGuard(json);
    CustomerPrincipal owner=new CustomerPrincipal(1L,"owner@fixture.example");
    ExportTask task(ExportKind kind,long cursor){return new ExportTask(UUID.randomUUID(),UUID.randomUUID(),kind,7,0,cursor,UUID.randomUUID());}
    ExportTask.Result result(Object data){return new ExportTask.Result(json.write(data),Instant.now(),Instant.now(),false);}
    Map<String,Object> doc(){var d=new LinkedHashMap<String,Object>();d.put("schemaVersion",1);d.put("scope","wishlist-page");d.put("complete",false);d.put("generatedAt",Instant.now());d.put("items",List.of());d.put("hasMore",false);d.put("nextCursor","0");d.put("omissions",List.of());return d;}
    @Test void acceptsBoundedEmptyPageButRejectsUnexpectedFields(){var d=doc();assertThat(guard.inspect(task(ExportKind.WISHLIST,0),result(d),owner)).isEmpty();d.put("rawMetadata","private");assertThatThrownBy(()->guard.inspect(task(ExportKind.WISHLIST,0),result(d),owner)).isInstanceOf(ExportFailure.class);}
    @Test void neverLetsNewNestedSecretsSlipIntoStoredChunks(){var d=doc();d.put("items",List.of(Map.of("productId",2,"createdAt",Instant.now(),"token","private")));assertThatThrownBy(()->guard.inspect(task(ExportKind.WISHLIST,0),result(d),owner)).isInstanceOf(ExportFailure.class);}
    @Test void paginationRequiresFullPageAndStrictlyDecreasingCursor(){var d=doc();d.put("hasMore",true);d.put("nextCursor","100");assertThatThrownBy(()->guard.inspect(task(ExportKind.WISHLIST,0),result(d),owner)).isInstanceOf(ExportFailure.class);d.put("items",java.util.stream.IntStream.range(0,50).mapToObj(n->Map.of("productId",n+1,"createdAt",Instant.now())).toList());assertThat(guard.inspect(task(ExportKind.WISHLIST,0),result(d),owner)).containsExactly(new ExportTask.Plan(ExportKind.WISHLIST,7,0,100));assertThatThrownBy(()->guard.inspect(task(ExportKind.WISHLIST,100),result(d),owner)).isInstanceOf(ExportFailure.class);}
    @Test void falseCompletenessWrongScopeAndNonNumericCursorFailClosed(){for(String key:List.of("complete","scope","nextCursor")){var d=doc();d.put(key,key.equals("complete")?true:"invalid");assertThatThrownBy(()->guard.inspect(task(ExportKind.WISHLIST,0),result(d),owner)).isInstanceOf(ExportFailure.class);}}
    @Test void projectionJsonPreservesInstantsDecimalAndUnicode(){String raw="{\"amount\":123456789.123456789,\"name\":\"Żółć\"}";assertThat(json.write(json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo(raw);assertThat(json.write(Map.of("time",Instant.parse("2026-10-28T12:00:00Z")))).contains("2026-10-28T12:00:00Z");}
}
