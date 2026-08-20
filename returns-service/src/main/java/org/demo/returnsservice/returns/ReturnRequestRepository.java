package org.demo.returnsservice.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {
    List<ReturnRequest> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<ReturnRequest> findAllByOrderByCreatedAtDesc();
    Optional<ReturnRequest> findByOrderIdAndCustomerIdAndRequestType(Long orderId, Long customerId, ReturnType requestType);
}
