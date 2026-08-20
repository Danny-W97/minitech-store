package org.demo.promotionservice.promotion;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PromotionCampaignRepository extends JpaRepository<PromotionCampaign, Long> {
    Optional<PromotionCampaign> findFirstByCodeAndActiveTrue(String code);
    List<PromotionCampaign> findAllByActiveTrueOrderByValidFromDesc();
}
