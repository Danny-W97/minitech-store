package org.demo.productservice.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
@ConditionalOnProperty(name = "price-history.snapshot.enabled", havingValue = "true", matchIfMissing = true)
public class ProductPriceHistorySnapshotJob {
    private static final Logger log = LoggerFactory.getLogger(ProductPriceHistorySnapshotJob.class);
    private final ProductStore products;

    public ProductPriceHistorySnapshotJob(ProductStore products) { this.products = products; }

    @EventListener(ApplicationReadyEvent.class)
    public void captureAfterStartup() { capture(); }

    @Scheduled(cron = "${price-history.snapshot.cron:0 5 0 * * *}", zone = "UTC")
    public void captureDaily() { capture(); }

    private void capture() {
        try {
            int inserted = products.captureDailyPriceHistory(LocalDate.now(ZoneOffset.UTC));
            log.info("Price history daily snapshot complete; inserted {} product prices", inserted);
        } catch (RuntimeException failure) {
            log.warn("Daily price history snapshot failed; the incomplete window will not be reported as complete", failure);
        }
    }
}
