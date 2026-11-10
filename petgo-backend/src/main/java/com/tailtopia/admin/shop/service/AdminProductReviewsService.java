package com.tailtopia.admin.shop.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 商品编辑页「评价」页签（V1.4.2 shop-admin Story 3.3 · AD-16 · J1 / J2）：只列<b>已发布</b>（含已隐藏）的评价，分页。只读。
 * 内容照原文展示；用户列取昵称（后台可见，App 端才打码）。不提供删除。
 */
@Service
public class AdminProductReviewsService {

    public static final int PAGE_SIZE = 20;

    private final JdbcTemplate jdbc;

    public AdminProductReviewsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行评价。{@code reason} 为隐藏原因码（可空：未隐藏，或 v1.4.2 前由 stag 工具隐藏）。 */
    public record Row(long id, int rating, String content, int imageCount, String userName, Instant createdAt,
            boolean hidden, String reason) {
    }

    /** 页签数据：摘要（总数 / 显示中 / 已隐藏 / 显示中平均分）+ 当前页。 */
    public record Tab(long productId, List<Row> rows, int page, int totalPages, long total, long visible, long hidden,
            Double averageVisible) {
    }

    public Tab page(long productId, int page) {
        Object[] stats = jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE hidden_at IS NULL), count(*) FILTER (WHERE hidden_at IS NOT NULL),
                       avg(rating) FILTER (WHERE hidden_at IS NULL)
                  FROM shop_reviews WHERE product_id = ? AND review_status = 'PUBLISHED'""",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4)}, productId);
        long total = (Long) stats[0];
        int totalPages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(Math.max(page, 0), totalPages - 1);
        List<Row> rows = jdbc.query("""
                SELECT r.id, r.rating, r.content, r.image_keys, u.nickname AS name,
                       r.created_at, r.hidden_at, r.hidden_reason
                  FROM shop_reviews r LEFT JOIN users u ON u.id = r.user_id
                 WHERE r.product_id = ? AND r.review_status = 'PUBLISHED'
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT ? OFFSET ?""", (rs, i) -> {
            String keys = rs.getString("image_keys");
            Timestamp created = rs.getTimestamp("created_at");
            return new Row(rs.getLong("id"), rs.getInt("rating"), rs.getString("content"),
                    keys == null || keys.isBlank() ? 0 : keys.split(",").length, rs.getString("name"),
                    created == null ? null : created.toInstant(), rs.getTimestamp("hidden_at") != null,
                    rs.getString("hidden_reason"));
        }, productId, PAGE_SIZE, p * PAGE_SIZE);
        Double avg = stats[3] == null ? null : ((Number) stats[3]).doubleValue();
        return new Tab(productId, rows, p, totalPages, total, (Long) stats[1], (Long) stats[2], avg);
    }
}
