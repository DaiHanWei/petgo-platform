package com.tailtopia.shop.returns.repository;

import com.tailtopia.shop.returns.domain.ReturnLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 退货行仓储（Story 5.1）。 */
public interface ReturnLineRepository extends JpaRepository<ReturnLine, Long> {

    List<ReturnLine> findByReturnRequestIdOrderByIdAsc(long returnRequestId);
}
