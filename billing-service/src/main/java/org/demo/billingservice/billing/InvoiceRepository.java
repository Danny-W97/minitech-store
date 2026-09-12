package org.demo.billingservice.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByOrderId(Long orderId);
    List<Invoice> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Invoice> findAllByOrderByCreatedAtDesc();
    List<Invoice> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE " +
           "(:query IS NULL OR :query = '' " +
           "OR LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(i.buyerName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(COALESCE(i.companyName, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(i.customerEmail) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(COALESCE(i.taxId, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR CONCAT('', i.orderId) LIKE CONCAT('%', :query, '%')) " +
           "ORDER BY i.createdAt DESC")
    List<Invoice> searchInvoices(@Param("query") String query, Pageable pageable);

    @Query("SELECT COUNT(i) FROM Invoice i WHERE " +
           "(:query IS NULL OR :query = '' " +
           "OR LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(i.buyerName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(COALESCE(i.companyName, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(i.customerEmail) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(COALESCE(i.taxId, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR CONCAT('', i.orderId) LIKE CONCAT('%', :query, '%'))")
    long countSearchInvoices(@Param("query") String query);
}

