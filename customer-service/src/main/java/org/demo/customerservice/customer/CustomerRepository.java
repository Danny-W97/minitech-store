package org.demo.customerservice.customer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmail(String email);

    @Query("select c from Customer c where c.id <= :ceiling and (lower(c.email) like :search "
            + "or lower(c.firstName) like :search or lower(c.lastName) like :search) order by c.id desc")
    java.util.List<Customer> page(@Param("ceiling") long ceiling, @Param("search") String search,
            org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from Customer customer where customer.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from Customer customer where customer.email = :email")
    Optional<Customer> findByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from Customer customer where customer.verificationTokenHash = :hash")
    Optional<Customer> findByVerificationTokenHashForUpdate(@Param("hash") String verificationTokenHash);
}
