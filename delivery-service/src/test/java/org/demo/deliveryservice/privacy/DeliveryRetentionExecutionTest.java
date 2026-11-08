package org.demo.deliveryservice.privacy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryRetentionExecutionTest {
    @Test void orderIdAloneNeverAuthorizesRedaction() {
        var ds=mock(DataSource.class); var manager=mock(PlatformTransactionManager.class);
        var result=new DeliveryCommerceRetentionRedactionWorker(ds,manager).redact(10);
        assertThat(result.outcome()).isEqualTo(DeliveryCommerceRetentionRedactionWorker.Outcome.BLOCKED);
        assertThat(result.redactedAt()).isNull();
        verifyNoInteractions(ds,manager);
    }
}
