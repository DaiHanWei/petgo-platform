package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.AdminContentRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** {@link ContentPostAdminSearch} 实现（Spring Data 按 Impl 命名约定织入）。仅为非 null 项加谓词。 */
public class ContentPostAdminSearchImpl implements ContentPostAdminSearch {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AdminContentRow> adminSearch(ContentType type, Long authorId, Instant from, Instant to,
            Boolean deleted, String keyword, int limit, int offset) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ContentPost> cq = cb.createQuery(ContentPost.class);
        Root<ContentPost> root = cq.from(ContentPost.class);

        List<Predicate> ps = new ArrayList<>();
        if (type != null) {
            ps.add(cb.equal(root.get("type"), type));
        }
        if (authorId != null) {
            ps.add(cb.equal(root.get("authorId"), authorId));
        }
        if (from != null) {
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            ps.add(cb.lessThan(root.get("createdAt"), to));
        }
        if (deleted != null) {
            ps.add(deleted ? cb.isNotNull(root.get("deletedAt")) : cb.isNull(root.get("deletedAt")));
        }
        if (keyword != null && !keyword.isBlank()) {
            // 转义 LIKE 元字符（\ 先转，再 % 与 _），避免 "100%"/"a_b" 之类被当通配符过度匹配。
            String esc = keyword.trim().toLowerCase()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            ps.add(cb.like(cb.lower(root.get("text")), "%" + esc + "%", '\\'));
        }
        cq.where(ps.toArray(Predicate[]::new));
        cq.orderBy(cb.desc(root.get("createdAt")));

        return em.createQuery(cq).setFirstResult(Math.max(offset, 0)).setMaxResults(limit)
                .getResultList().stream()
                .map(ContentPostAdminSearchImpl::toRow)
                .toList();
    }

    @Override
    public AdminContentRow adminRowById(long postId) {
        ContentPost p = em.find(ContentPost.class, postId);
        return p == null ? null : toRow(p);
    }

    private static AdminContentRow toRow(ContentPost p) {
        return new AdminContentRow(p.getId(), p.getType(), p.getAuthorId(),
                p.getText(), p.getDeletedAt() != null, p.getCreatedAt(), p.getImageUrls());
    }
}
