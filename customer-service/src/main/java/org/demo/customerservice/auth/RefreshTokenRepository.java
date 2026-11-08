package org.demo.customerservice.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

import java.util.Optional;
import java.util.List;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    @Query("select token.customerId from RefreshToken token where token.tokenHash = :hash")
    Optional<Long> customerIdForHash(@Param("hash") String hash);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken token set token.revokedAt = :now where token.familyId = :family and token.revokedAt is null")
    int revokeFamily(@Param("family") String family, @Param("now") Instant now);
    List<RefreshToken> findByCustomerIdAndRevokedAtIsNull(Long customerId);
}
