package org.demo.promotionservice.promotion;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromotionPriceHistoryRepository extends JpaRepository<PromotionPriceHistory, Long> {
    List<PromotionPriceHistory> findTop100ByProductIdOrderByRotationDateDescRecordedAtDesc(Long productId);
}
