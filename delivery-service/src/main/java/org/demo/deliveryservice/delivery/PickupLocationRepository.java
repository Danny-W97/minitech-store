package org.demo.deliveryservice.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PickupLocationRepository extends JpaRepository<PickupLocation, String> {
    List<PickupLocation> findAllByActiveTrueOrderByCodeAsc();
}
