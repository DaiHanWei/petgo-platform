package com.tailtopia.admin.audit.repository;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * {@link AdminAuditLogRepositoryCustom} 实现（Spring Data 按 {@code Impl} 命名约定自动织入）。
 * 仅为非 null 筛选项添加谓词，避免无类型 null 参数；分页另发 count 查询。
 */
public class AdminAuditLogRepositoryImpl implements AdminAuditLogRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Page<AdminAuditLog> search(Instant from, Instant to, Long actor, String action,
            Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        // 数据查询（倒序分页）。
        CriteriaQuery<AdminAuditLog> cq = cb.createQuery(AdminAuditLog.class);
        Root<AdminAuditLog> root = cq.from(AdminAuditLog.class);
        cq.where(predicates(cb, root, from, to, actor, action).toArray(Predicate[]::new));
        cq.orderBy(cb.desc(root.get("createdAt")));
        List<AdminAuditLog> content = em.createQuery(cq)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // 计数查询（同条件）。
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<AdminAuditLog> countRoot = countQuery.from(AdminAuditLog.class);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countRoot, from, to, actor, action).toArray(Predicate[]::new));
        long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private List<Predicate> predicates(CriteriaBuilder cb, Root<AdminAuditLog> root,
            Instant from, Instant to, Long actor, String action) {
        List<Predicate> ps = new ArrayList<>();
        if (from != null) {
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            ps.add(cb.lessThan(root.get("createdAt"), to));
        }
        if (actor != null) {
            ps.add(cb.equal(root.get("actorAccountId"), actor));
        }
        if (action != null) {
            ps.add(cb.equal(root.get("actionType"), action));
        }
        return ps;
    }
}
