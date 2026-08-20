package org.demo.promotionservice.promotion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromotionCodeRepository extends JpaRepository<PromotionCode, Long> {
    Optional<PromotionCode> findByCodeIgnoreCase(String code);
    Optional<PromotionCode> findByCodeIgnoreCaseAndActiveTrue(String code);
    List<PromotionCode> findAllByOrderByCodeAsc();

    @Modifying
    @Query("update PromotionCode c set c.redeemedCount = c.redeemedCount + 1 " +
            "where upper(c.code) = upper(:code) " +
            "and (c.maxRedemptions is null or c.redeemedCount < c.maxRedemptions)")
    int incrementRedeemedCountIfAvailable(@Param("code") String code);
}
