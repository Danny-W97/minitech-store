package org.demo.recommendationservice.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.QueryHint;
import java.time.Instant;

public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequest, Long> {
    @Modifying
    @Transactional
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    @Query(value = """
            DELETE FROM recommendation_requests WHERE id IN (
                SELECT id FROM recommendation_requests WHERE requested_at < :cutoff
                ORDER BY requested_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Query("select min(request.requestedAt) from RecommendationRequest request")
    Instant oldestRequestedAt();
}
