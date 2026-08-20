package org.demo.promotionservice.promotion;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PromotionPriceHistoryRepository extends JpaRepository<PromotionPriceHistory, Long> {
    List<PromotionPriceHistory> findTop30ByProductIdOrderByRotationDateDesc(Long productId);
    boolean existsByProductIdAndCampaignCodeAndRotationDate(Long productId, String campaignCode,
                                                             java.time.LocalDate rotationDate);

    @Query("select min(h.promotionalPrice) from PromotionPriceHistory h "
            + "where h.productId = :productId and h.rotationDate >= :from")
    Optional<BigDecimal> findLowestPriceSince(@Param("productId") Long productId,
                                              @Param("from") LocalDate from);
}
