package org.demo.customerservice.mfa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TotpMethodRepository extends JpaRepository<TotpMethod, Long> {
    Optional<TotpMethod> findByCustomerId(Long customerId);
    Optional<TotpMethod> findByCustomerIdAndEnabledTrue(Long customerId);
}
