package org.demo.returnsservice.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from ReturnRequest r where r.id=:id")
    Optional<ReturnRequest> lockId(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Query("select r.id from ReturnRequest r where r.id<=:ceiling and (:customerId is null or r.customerId=:customerId) and (:status is null or r.status=:status) order by r.id desc")
    List<Long> pageIds(@org.springframework.data.repository.query.Param("ceiling") long ceiling,@org.springframework.data.repository.query.Param("customerId") Long customerId,
            @org.springframework.data.repository.query.Param("status") ReturnStatus status,org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths="items")
    @org.springframework.data.jpa.repository.Query("select r from ReturnRequest r where r.id in :ids")
    List<ReturnRequest> withItems(@org.springframework.data.repository.query.Param("ids") List<Long> ids);
    List<ReturnRequest> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<ReturnRequest> findAllByOrderByCreatedAtDesc();

    @org.springframework.data.jpa.repository.Query("select r from ReturnRequest r where r.id <= :ceiling "
            + "and (:customerId is null or r.customerId = :customerId) and (:status is null or r.status = :status) order by r.id desc")
    List<ReturnRequest> page(@org.springframework.data.repository.query.Param("ceiling") long ceiling,
            @org.springframework.data.repository.query.Param("customerId") Long customerId,
            @org.springframework.data.repository.query.Param("status") ReturnStatus status, org.springframework.data.domain.Pageable pageable);
    Optional<ReturnRequest> findByOrderIdAndCustomerIdAndRequestType(Long orderId, Long customerId, ReturnType requestType);
}
