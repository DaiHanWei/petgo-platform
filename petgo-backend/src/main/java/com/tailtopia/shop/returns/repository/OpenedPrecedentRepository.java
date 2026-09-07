package com.tailtopia.shop.returns.repository;

import com.tailtopia.shop.returns.domain.OpenedPrecedent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 开封判定判例仓储（Story 5.6）。 */
public interface OpenedPrecedentRepository extends JpaRepository<OpenedPrecedent, Long> {

    List<OpenedPrecedent> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /**
     * 按情形关键词检索（大小写不敏感）。
     *
     * <p>V1 只做 LIKE：判例量在 SKU ≤ 30 的规模下是两位数，全文索引是过度设计。
     */
    @Query("""
            SELECT p FROM OpenedPrecedent p
            WHERE LOWER(p.situation) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(p.rationale) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<OpenedPrecedent> search(@Param("q") String q, Pageable pageable);
}
