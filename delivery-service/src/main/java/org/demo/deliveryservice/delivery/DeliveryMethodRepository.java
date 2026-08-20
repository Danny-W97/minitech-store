package org.demo.deliveryservice.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryMethodRepository extends JpaRepository<DeliveryMethod, Long> {

    List<DeliveryMethod> findAllByActiveTrueOrderByBasePriceAsc();

    Optional<DeliveryMethod> findByCodeAndActiveTrue(String code);
}
