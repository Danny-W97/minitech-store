package org.demo.customerservice.mfa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.List;

import java.util.Optional;

public interface TotpMethodRepository extends JpaRepository<TotpMethod, Long> {
    Optional<TotpMethod> findByCustomerId(Long customerId);
    Optional<TotpMethod> findByCustomerIdAndEnabledTrue(Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TotpMethod t where t.id > :afterId order by t.id")
    List<TotpMethod> migrationBatch(long afterId, Pageable pageable);
}
