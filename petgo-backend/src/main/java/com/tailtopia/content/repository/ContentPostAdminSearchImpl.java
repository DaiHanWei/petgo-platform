package com.tailtopia.content.repository;

import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.AdminContentRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** {@link ContentPostAdminSearch} 实现（Spring Data 按 Impl 命名约定织入）。仅为非 null 项加谓词。 */
public class ContentPostAdminSearchImpl implements ContentPostAdminSearch {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AdminContentRow> adminSearch(ContentType type, Long authorId, Instant from, Instant to,
            Boolean deleted, String keyword, String sort, int limit, int offset) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<ContentPost> root = cq.from(ContentPost.class);
        // 评论数：comments 表未删计数（后台全量口径，不按 viewer 过滤）。相关子查询，随主查询一次取回。
        Subquery<Long> commentCount = cq.subquery(Long.class);
        Root<Comment> c = commentCount.from(Comment.class);
        commentCount.select(cb.count(c))
                .where(cb.equal(c.get("postId"), root.get("id")), cb.isNull(c.get("deletedAt")));
        cq.multiselect(root, commentCount);

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
        if ("comments_desc".equals(sort)) {
            cq.orderBy(cb.desc(commentCount), cb.desc(root.get("createdAt")));
        } else if ("comments_asc".equals(sort)) {
            cq.orderBy(cb.asc(commentCount), cb.desc(root.get("createdAt")));
        } else {
            cq.orderBy(cb.desc(root.get("createdAt")));
        }

        return em.createQuery(cq).setFirstResult(Math.max(offset, 0)).setMaxResults(limit)
                .getResultList().stream()
                .map(r -> toRow((ContentPost) r[0], ((Number) r[1]).longValue()))
                .toList();
    }

    @Override
    public AdminContentRow adminRowById(long postId) {
        ContentPost p = em.find(ContentPost.class, postId);
        if (p == null) {
            return null;
        }
        long count = em.createQuery(
                "select count(c) from Comment c where c.postId = :pid and c.deletedAt is null", Long.class)
                .setParameter("pid", postId).getSingleResult();
        return toRow(p, count);
    }

    private static AdminContentRow toRow(ContentPost p, long commentCount) {
        return new AdminContentRow(p.getId(), p.getType(), p.getAuthorId(),
                p.getText(), p.getDeletedAt() != null, p.getCreatedAt(), p.getImageUrls(), commentCount);
    }
}
