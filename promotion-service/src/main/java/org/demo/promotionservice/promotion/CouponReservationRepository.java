package org.demo.promotionservice.promotion;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CouponReservationRepository extends JpaRepository<CouponReservation,Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CouponReservation r where r.orderId=:id") Optional<CouponReservation> lockId(@Param("id") Long id);
    long countByCodeAndState(String code,String state);
    @Query("select r from CouponReservation r where r.orderId<=:ceiling and (:code is null or r.code=:code) and (:state is null or r.state=:state) order by r.orderId desc")
    List<CouponReservation> page(@Param("ceiling") long ceiling,@Param("code") String code,@Param("state") String state,org.springframework.data.domain.Pageable page);
}
