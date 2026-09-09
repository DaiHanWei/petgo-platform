package com.tailtopia.admin.dashboard.metrics;

import com.tailtopia.admin.dashboard.domain.MetricScope;

/**
 * 18 项指标 SQL 的公共片段（V1.3.0 Story 3.2）。口径唯一来源：PRD §1 ①-a + 参考 SQL {@code 内容运营所需数据-20260831.sql}。
 * <ul>
 * <li>切日：{@link #day(String)} → {@code (ts AT TIME ZONE 'Asia/Jakarta')::date}（OQ-B3：参考 SQL 的裸 {@code ::date} 随连接时区漂移，全部改显式 WIB）</li>
 * <li>真实用户：{@link SyntheticAccountSql#EXCLUDE_WHERE}（3-1 单一出口），{@link #realUser(String)} 只做别名替换</li>
 * <li>可见帖：{@link #VISIBLE_POST_WHERE}；有效评论：{@link #VALID_COMMENT_WHERE}（D-38：VISIBLE 且未删）</li>
 * <li>互动源 CTE {@link #interactionsCte(MetricScope)}：赞 ∪ 有效评论；REAL 口径 = 互动者真实 且 帖子作者真实（D-29）</li>
 * </ul>
 */
public final class MetricSql {

    /** 看板日 = WIB 自然日。 */
    public static final String ZONE = "Asia/Jakarta";

    /** 命名参数：目标 WIB 自然日（绑 {@code LocalDate} → DATE）。 */
    public static final String PARAM_DAY = "d";

    /** 可见帖（别名 {@code p = content_posts}）。 */
    public static final String VISIBLE_POST_WHERE = "p.status = 'PUBLISHED' AND p.deleted_at IS NULL";

    /** 有效评论（别名 {@code c = comments}；D-38：已公开且未删）。 */
    public static final String VALID_COMMENT_WHERE = "c.moderation_status = 'VISIBLE' AND c.deleted_at IS NULL";

    /** 互动得分：赞 ×1 + 有效评论 ×5（别名 {@code i = interactions}）。 */
    public static final String SCORE_CASE = "CASE WHEN i.source = 'like' THEN 1 ELSE 5 END";

    private MetricSql() {
    }

    /** {@code (expr AT TIME ZONE 'Asia/Jakarta')::date}。 */
    public static String day(String tsExpr) {
        return "(" + tsExpr + " AT TIME ZONE '" + ZONE + "')::date";
    }

    /** {@link SyntheticAccountSql#EXCLUDE_WHERE} 换表别名（默认别名 {@code u}）。 */
    public static String realUser(String alias) {
        return SyntheticAccountSql.EXCLUDE_WHERE.replaceAll("\\bu\\.", alias + ".");
    }

    /** REAL 口径下给 {@code content_posts p} 追加「作者为真实用户」JOIN；ALL 口径为空串。 */
    public static String realAuthorJoin(MetricScope scope) {
        return scope == MetricScope.REAL ? " JOIN users u ON u.id = p.author_id AND " + SyntheticAccountSql.EXCLUDE_WHERE : "";
    }

    /**
     * 互动源 CTE（不含 {@code WITH}）：{@code interactions(post_id, actor_id, source, created_at)}。
     * ALL = 参考 SQL {@code interactions_raw}（评论按 D-38 过滤）；REAL 再要求互动者与帖子作者都是真实用户。
     */
    public static String interactionsCte(MetricScope scope) {
        String raw = "SELECT l.post_id, l.user_id AS actor_id, 'like' AS source, l.created_at FROM content_likes l"
                + " UNION ALL "
                + "SELECT c.post_id, c.author_id AS actor_id, 'comment' AS source, c.created_at FROM comments c WHERE "
                + VALID_COMMENT_WHERE;
        if (scope != MetricScope.REAL) {
            return "interactions AS (" + raw + ")";
        }
        return "interactions AS (SELECT i.post_id, i.actor_id, i.source, i.created_at FROM (" + raw + ") i"
                + " JOIN users ua ON ua.id = i.actor_id AND " + realUser("ua")
                + " JOIN content_posts ip ON ip.id = i.post_id"
                + " JOIN users up ON up.id = ip.author_id AND " + realUser("up") + ")";
    }

    /** 某帖截至 {@code :d} 24:00 的累计得分（LATERAL 子查询，别名 {@code s.score}）。 */
    public static String postScoreLateral() {
        return " LEFT JOIN LATERAL (SELECT coalesce(sum(" + SCORE_CASE + "), 0) AS score FROM interactions i"
                + " WHERE i.post_id = p.id AND " + day("i.created_at") + " <= :" + PARAM_DAY + ") s ON TRUE";
    }
}
