package org.demo.promotionservice.promotion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemption, Long> {
    boolean existsByCodeIgnoreCaseAndOrderId(String code, Long orderId);
}
