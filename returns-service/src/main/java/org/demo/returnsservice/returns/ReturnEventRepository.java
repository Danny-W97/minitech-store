package org.demo.returnsservice.returns;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface ReturnEventRepository extends JpaRepository<ReturnEvent,Long> {
    Optional<ReturnEvent> findByReturnIdAndOperationId(Long id,UUID operation);
    @Query("select e from ReturnEvent e where e.returnId=:id and e.id<=:ceiling order by e.id desc")
    List<ReturnEvent> page(@Param("id") Long id,@Param("ceiling") long ceiling,org.springframework.data.domain.Pageable page);
}
