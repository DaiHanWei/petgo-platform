package com.tailtopia.admin.comment.service;

import com.tailtopia.admin.comment.dto.CommentInspectRow;
import com.tailtopia.admin.comment.dto.CommentSummary;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台评论内容管理（Story 9.9，后台§7.5）——两线合并后本 service 只承担【列表读取】。
 *
 * <p>下架/恢复动作在合并时切到内容审核线 {@code AdminCommentManageService}（FR-55A 语义：
 * 可见性态迁移 VISIBLE→TAKEN_DOWN 作者仍可见 + CONTENT_REMOVED 通知 + 违规计数 + 必填原因审计），
 * 取代本线原「软删即下架」实现——软删会连作者一起隐藏、且绕过违规计数/通知，与审核模型冲突。
 *
 * <h2>V1.3.0 Story 7.2</h2>
 * 列表从「最近 200 条、无筛选、无分页」升级为筛选 + 分页 + 摘要条，并给抽屉提供单条详情。
 * 行上的所属帖子摘要与作者昵称<b>整页一次批量取</b>（逐行查就是 N+1）。
 */
@Service
public class AdminCommentModerationService {

    /** 每页 20（Story 7.2 · AC3，与其它模板 B 列表同口径）。 */
    public static final int PAGE_SIZE = 20;

    /** 后台全站按雅加达解释时间（摘要条的「今日」）。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /**
     * 摘要条的 where —— ⚠️ 必须与 {@link #spec} 的条件<b>逐条对齐</b>，否则摘要上的数与表格里的行对不上。
     *
     * <p>状态一栏两种语义：{@code DELETED} 指用户自删 / 级联软删；其余是审核线可见性态（且要求未软删）。
     * 🔴 「已下架」与「已删除」不是一回事，不要合并（Dev Notes）。
     */
    private static final String SUMMARY_WHERE = """
            (:statusMode IS NULL
               OR (:statusMode = 'DELETED' AND c.deleted_at IS NOT NULL)
               OR (:statusMode <> 'DELETED' AND c.deleted_at IS NULL AND c.moderation_status = :statusMode))
              AND (:postId IS NULL OR c.post_id = :postId)
              AND (:q IS NULL OR c.body ILIKE :q)
            """;

    private static final String SUMMARY_SQL = """
            SELECT COUNT(*)                                                                          AS total,
                   COUNT(*) FILTER (WHERE (c.created_at AT TIME ZONE 'Asia/Jakarta')::date = :today) AS today_new,
                   COUNT(*) FILTER (WHERE c.moderation_status = 'TAKEN_DOWN' AND c.deleted_at IS NULL) AS taken_down
            FROM comments c
            WHERE
            """ + SUMMARY_WHERE; // ⚠️ 文本块剥尾随空格：WHERE 必须单独一行，否则拼成 WHEREc.

    private final CommentRepository comments;
    private final ContentService contentService;
    private final AccountQueryService accountQuery;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminCommentModerationService(CommentRepository comments, ContentService contentService,
            AccountQueryService accountQuery, NamedParameterJdbcTemplate jdbc) {
        this.comments = comments;
        this.contentService = contentService;
        this.accountQuery = accountQuery;
        this.jdbc = jdbc;
    }

    /**
     * 一页评论 + 总数 + 有无下一页。
     *
     * @param page 实际返回的页码 —— 页码越界时会回退到最后一页，界面上的分页器要用<b>这个</b>，
     *             否则「上一页」的链接会从一个不存在的页码往前算
     */
    public record InspectPage(List<CommentInspectRow> rows, long total, boolean hasNext, int page) {
    }

    /**
     * 筛选 + 分页（Story 7.2 · AC2 / AC3）：状态 / 帖子 ID / 关键词，时间倒序。
     *
     * <p>⚠️ 状态取值非法时<b>当作不筛</b>（手改 URL 不该出 500；与 6.4 的宽松解析同一处理）。
     */
    @Transactional(readOnly = true)
    public InspectPage search(String status, Long postId, String q, int page) {
        Specification<Comment> spec = spec(status, postId, q);
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Page<Comment> found = comments.findAll(spec, PageRequest.of(Math.max(page, 0), PAGE_SIZE, sort));
        // ⚠️ 页码越界回退到最后一页：抽屉里处置掉「第 N 页最后一行」后，列表会按 data-page=N 重拉，
        //    那时这一页已经空了 —— 而空态里没有分页器，运营就此卡在一个翻不回去的空页上。
        if (found.isEmpty() && found.getTotalElements() > 0 && page > 0) {
            int last = (int) ((found.getTotalElements() - 1) / PAGE_SIZE);
            found = comments.findAll(spec, PageRequest.of(last, PAGE_SIZE, sort));
        }
        return new InspectPage(enrich(found.getContent()), found.getTotalElements(), found.hasNext(),
                found.getNumber());
    }

    /** 摘要条：总评论数 · 今日新增（WIB）· 已下架，随当前筛选联动（单条聚合）。 */
    @Transactional(readOnly = true)
    public CommentSummary summary(String status, Long postId, String q) {
        CommentModerationStatus parsed = parseStatus(status);
        String statusMode = "DELETED".equals(status) ? "DELETED" : (parsed == null ? null : parsed.name());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("statusMode", statusMode, java.sql.Types.VARCHAR)
                .addValue("postId", postId, java.sql.Types.BIGINT)
                .addValue("q", likeParam(q), java.sql.Types.VARCHAR)
                .addValue("today", LocalDate.now(WIB));
        return jdbc.query(SUMMARY_SQL, params, rs -> rs.next()
                ? new CommentSummary(rs.getLong("total"), rs.getLong("today_new"), rs.getLong("taken_down"))
                : new CommentSummary(0, 0, 0));
    }

    /** 单条（抽屉 / 处置后的 oob 行）；不存在 → 404。 */
    @Transactional(readOnly = true)
    public CommentInspectRow detail(long id) {
        Comment c = comments.findById(id)
                .orElseThrow(() -> AppException.notFound("评论不存在").code("admin.err.comment.notFound"));
        return enrich(List.of(c)).get(0);
    }

    /** 帖子摘要 + 作者昵称整页一次取回。 */
    private List<CommentInspectRow> enrich(List<Comment> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, AdminContentRow> posts = contentService
                .adminRowsByIds(rows.stream().map(Comment::getPostId).distinct().toList()).stream()
                .collect(Collectors.toMap(AdminContentRow::id, r -> r, (a, b) -> a));
        Map<Long, AuthorView> authors = authorViews(rows.stream().map(Comment::getAuthorId).toList());
        List<CommentInspectRow> out = new ArrayList<>();
        for (Comment c : rows) {
            AdminContentRow p = posts.get(c.getPostId());
            AuthorView a = authors.get(c.getAuthorId());
            out.add(new CommentInspectRow(c.getId(), c.getPostId(), c.getAuthorId(), c.getBody(),
                    c.isDeleted(),
                    c.getModerationStatus() == null ? CommentModerationStatus.VISIBLE.name()
                            : c.getModerationStatus().name(),
                    c.getCreatedAt(),
                    p == null ? null : p.textPreview(),
                    p == null || p.imageUrls() == null || p.imageUrls().isEmpty() ? null : p.imageUrls().get(0),
                    a == null ? null : a.nickname(), a != null && a.deleted()));
        }
        return out;
    }

    private Map<Long, AuthorView> authorViews(Collection<Long> ids) {
        var distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return distinct.isEmpty() ? Map.of() : accountQuery.findAuthorViews(distinct);
    }

    /** ⚠️ 条件必须与 {@link #SUMMARY_WHERE} 逐条对齐。 */
    private static Specification<Comment> spec(String status, Long postId, String q) {
        CommentModerationStatus parsed = parseStatus(status);
        boolean deletedOnly = "DELETED".equals(status);
        String keyword = likeParam(q);
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (deletedOnly) {
                ps.add(cb.isNotNull(root.get("deletedAt")));
            } else if (parsed != null) {
                ps.add(cb.isNull(root.get("deletedAt")));
                ps.add(cb.equal(root.get("moderationStatus"), parsed));
            }
            if (postId != null) {
                ps.add(cb.equal(root.get("postId"), postId));
            }
            if (keyword != null) {
                ps.add(cb.like(cb.lower(root.get("body")), keyword.toLowerCase(java.util.Locale.ROOT), '\\'));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(Predicate[]::new));
        };
    }

    private static CommentModerationStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "DELETED".equals(status)) {
            return null;
        }
        try {
            return CommentModerationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null; // 手改 URL 传了不认识的状态：当作不筛，不出 500
        }
    }

    /** 关键词转成 LIKE 参数（% / _ / \ 按字面匹配）；空 → null。 */
    private static String likeParam(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
