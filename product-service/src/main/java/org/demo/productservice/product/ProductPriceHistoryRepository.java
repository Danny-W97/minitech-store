package org.demo.productservice.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistoryEvent, Long> {
    boolean existsByProductIdAndRotationDateAndPriceRevisionAndLayoutRevisionAndSourceType(
            Long productId, LocalDate rotationDate, long priceRevision, long layoutRevision, String sourceType);

    List<ProductPriceHistoryEvent> findTop100ByProductIdOrderByRotationDateDescRecordedAtDescIdDesc(Long productId);

    @Query("select distinct h.productId from ProductPriceHistoryEvent h "
            + "where h.rotationDate = :date and h.layoutRevision = :layoutRevision and h.sourceType = :sourceType")
    List<Long> findProductIdsByRotationDateAndLayoutRevisionAndSourceType(@Param("date") LocalDate date,
            @Param("layoutRevision") long layoutRevision, @Param("sourceType") String sourceType);

    @Query("select min(h.effectivePrice) from ProductPriceHistoryEvent h where h.productId = :productId "
            + "and h.rotationDate between :from and :to and h.sourceType <> 'BASELINE'")
    Optional<BigDecimal> findLowestEffectivePrice(@Param("productId") Long productId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    @Query("select count(distinct h.rotationDate) from ProductPriceHistoryEvent h where h.productId = :productId "
            + "and h.rotationDate between :from and :to and h.sourceType in ('DAILY', 'CREATE')")
    int countObservedDays(@Param("productId") Long productId, @Param("from") LocalDate from,
                          @Param("to") LocalDate to);

    @Query("select distinct h.rotationDate from ProductPriceHistoryEvent h where h.productId = :productId "
            + "and h.rotationDate between :from and :to and h.sourceType in ('DAILY', 'CREATE') order by h.rotationDate")
    List<LocalDate> findObservedDates(@Param("productId") Long productId, @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);
}
