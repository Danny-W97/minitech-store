package org.demo.customerservice.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {
    List<RecoveryCode> findByCustomerIdAndUsedAtIsNull(Long customerId);
    Optional<RecoveryCode> findByCustomerIdAndCodeHashAndUsedAtIsNull(Long customerId, String codeHash);
    void deleteByCustomerId(Long customerId);
}
