package com.tailtopia.admin.stats.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.stats.dto.InteractionScoreRow;
import com.tailtopia.admin.stats.dto.StatsScope;
import com.tailtopia.shared.error.AppException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容互动积分与双口径统计（V1.1.6 Story 15.1 · AB-3G）。
 *
 * <h2>为什么要有它</h2>
 * ① 装饰标签（11-2）目前**没有任何排序工具**辅助运营挑「本周最佳/最佳萌宠」候选；
 * ② 管理层需要一个量化指标观察内容质量与平台活跃度趋势。
 *
 * <h2>🔴 两个口径回答的是**不同问题**</h2>
 * 不是同一个数的两种算法 —— 混为一谈会让判读失真。所以它们是**互斥单选**：
 * <ul>
 *   <li><b>口径A</b>：按帖子**发布时间**筛区间，积分取**至今累计**
 *       → 「这段时间发的内容质量如何」</li>
 *   <li><b>口径B</b>：**不筛发布时间**，积分只计**区间内新增**的赞/评
 *       → 「这段时间平台活跃情况」，能反映<b>老帖子被重新带火</b>这类口径A看不到的情况</li>
 * </ul>
 *
 * <h2>🛡 未同步为公开的 Diary 不纳入（AC4）</h2>
 * 它们只在作者自己的成长档案时间线里展示、不在公开 Feed 流通 ——
 * 其赞/评量<b>既不代表内容质量也不代表平台公开活跃度</b>，且不可能成为装饰标签候选
 * （装饰标签的意义在于公开曝光）。
 *
 * <p>✅ <b>静态过滤即可</b>（AC5）：FR-83 已明确同步状态**发布时一次性确定、发布后不可更改**
 * ⇒ 一条内容的公开/私密属性在其整个生命周期内**恒定** ⇒
 * <b>不存在"统计时点与互动发生时点状态不一致"的问题</b>，
 * 所以**不需要**维护状态变更历史。
 *
 * <p>⚠️ 量级提示：同步开关已于 2026-07-30 反转为**默认开启**，被排除的只是"作者主动关开关"
 * 的少数 —— 结果集接近全量内容，所以两条查询都**在数据库里聚合 + 分页**，不往内存里捞。
 */
@Service
public class InteractionScoreService {

    /** 运营选的日期按这个时区解释（与后台其余四处一致）。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /** 一页多少行。榜单是给人看的，翻太多页没意义。 */
    private static final int PAGE_SIZE = 50;

    /**
     * 公开口径的过滤条件。
     *
     * <p>🛡 三个条件缺一不可：未软删 / 已发布 / 公开。
     * ⚠️ <b>{@code visibility} 那一条就是 AC4</b> —— 漏了它，作者主动关掉同步的那些日记
     * 会带着它们的赞评进榜，而那些数既不代表内容质量也不代表公开活跃度。
     */
    private static final String PUBLIC_FILTER =
            " p.deleted_at is null and p.status = 'PUBLISHED' and p.visibility = 'PUBLIC' ";

    private final EntityManager em;
    private final AdminAuditService audit;

    public InteractionScoreService(EntityManager em, AdminAuditService audit) {
        this.em = em;
        this.audit = audit;
    }

    /**
     * 查一页榜单。
     *
     * @param from 起始日（WIB 当地日期，含）
     * @param to   结束日（WIB 当地日期，含）
     */
    @Transactional(readOnly = true)
    public List<InteractionScoreRow> rank(StatsScope scope, LocalDate from, LocalDate to, int page) {
        return rank(scope, from, to, null, page);
    }

    /**
     * 查一页榜单，可按作者过滤。
     *
     * <p>🔴 <b>作者过滤是必需的，不是锦上添花</b>：榜单是全平台的 top N，
     * 而运营挑「本周最佳」候选时常常是**盯着某个 IP 号**看
     * （"这个号这周发的哪条最好"）—— 不按作者筛的话，那个号的内容
     * 会被全平台的热帖挤出前 50 页，功能等于用不上。
     *
     * @param authorId 可空
     */
    @Transactional(readOnly = true)
    public List<InteractionScoreRow> rank(StatsScope scope, LocalDate from, LocalDate to,
            Long authorId, int page) {
        Range range = Range.of(from, to);
        return query(scope, range, authorId, PAGE_SIZE, Math.max(page, 0) * PAGE_SIZE);
    }

    /**
     * 导出（AC1：供管理层经营汇报）。
     *
     * <p>🔴 <b>记审计</b>：导出是把数据批量带出系统，事后要能回答"这份表是谁什么时候导的"。
     * ⚠️ 因此本方法**不是** {@code readOnly} —— 它要写一行审计。
     * （11-4 踩过这个：{@code readOnly=true} 下写审计会抛
     * "cannot execute INSERT in a read-only transaction"。）
     */
    @Transactional
    public String exportCsv(StatsScope scope, LocalDate from, LocalDate to, Long authorId,
            long actorAccountId) {
        Range range = Range.of(from, to);
        // 导出不分页：管理层要的是完整区间。上限 5000 行防手滑（比如把起止日期填成三年）。
        List<InteractionScoreRow> rows = query(scope, range, authorId, 5000, 0);
        StringBuilder csv = new StringBuilder(
                "内容ID,类型,作者ID,正文摘要,发布时间,点赞,评论,互动积分\n");
        for (InteractionScoreRow r : rows) {
            csv.append(r.postId()).append(',')
                    .append(r.type()).append(',')
                    .append(r.authorId() == null ? "" : r.authorId()).append(',')
                    .append(csvCell(r.textPreview())).append(',')
                    .append(r.publishedAt()).append(',')
                    .append(r.likes()).append(',')
                    .append(r.comments()).append(',')
                    .append(r.score()).append('\n');
        }
        audit.record(actorAccountId, "CONTENT_STATS_EXPORT", "content_post", "-",
                "scope=" + scope + " from=" + from + " to=" + to
                        + (authorId == null ? "" : " authorId=" + authorId)
                        + " rows=" + rows.size());
        return csv.toString();
    }

    // ——————————————————— 内部 ———————————————————

    /** 起止日 → UTC 半开区间。 */
    private record Range(Instant fromInclusive, Instant toExclusive) {

        static Range of(LocalDate from, LocalDate to) {
            if (from == null || to == null) {
                throw AppException.validation("请选择统计的起止日期");
            }
            if (to.isBefore(from)) {
                throw AppException.validation("结束日期不能早于开始日期");
            }
            // 🔴 半开区间 [from 00:00, to+1 00:00)：结束日**含当天**。
            //    写成闭区间会漏掉结束日当天 00:00 之后的全部互动 ——
            //    而运营选"到今天"时，今天恰恰是数据最多的一天。
            return new Range(from.atStartOfDay(WIB).toInstant(),
                    to.plusDays(1).atStartOfDay(WIB).toInstant());
        }
    }

    private List<InteractionScoreRow> query(StatsScope scope, Range range, Long authorId,
            int limit, int offset) {
        String base = scope == StatsScope.CONTENT_QUALITY
                ? contentQualitySql()
                : platformActivitySql();
        // ⚠️ 用**显式占位符**而不是 replace(" order by ")：
        //    后者依赖"SQL 里恰好有这个词、且只有一处"，改一次 SQL 结构就会静默拼错位置
        //    （我第一版就是那么写的，反证时改 SQL 直接拼出了语法错）。
        String sql = base.replace("{authorFilter}",
                authorId == null ? "" : " and p.author_id = :authorId ");
        Query q = em.createNativeQuery(sql);
        if (authorId != null) {
            q.setParameter("authorId", authorId);
        }
        q.setParameter("from", java.sql.Timestamp.from(range.fromInclusive()));
        q.setParameter("to", java.sql.Timestamp.from(range.toExclusive()));
        q.setParameter("lim", limit);
        q.setParameter("off", offset);
        List<?> raw = q.getResultList();
        List<InteractionScoreRow> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            Object[] r = (Object[]) o;
            long likes = ((Number) r[5]).longValue();
            long comments = ((Number) r[6]).longValue();
            out.add(new InteractionScoreRow(
                    ((Number) r[0]).longValue(),
                    com.tailtopia.content.domain.ContentType.valueOf((String) r[1]),
                    r[2] == null ? null : ((Number) r[2]).longValue(),
                    (String) r[3],
                    toInstant(r[4]),
                    likes, comments, InteractionScoreRow.scoreOf(likes, comments)));
        }
        return out;
    }

    /**
     * 口径A：按**发布时间**筛区间，赞/评取**至今累计**。
     *
     * <p>⚠️ 赞与评各自子查询、**不 join 两张表**：两个一对多 join 在一起会让计数相乘
     * （3 赞 × 2 评 = 6/6），而那种错看起来只是"数偏大"，不会报错。
     */
    private static String contentQualitySql() {
        return """
                select p.id, p.type, p.author_id, left(coalesce(p.text, ''), 60), p.created_at,
                       (select count(*) from content_likes l where l.post_id = p.id) as likes,
                       (select count(*) from comments c
                         where c.post_id = p.id and c.deleted_at is null) as comments
                  from content_posts p
                 where """ + PUBLIC_FILTER + """
                   and p.created_at >= :from and p.created_at < :to
                   {authorFilter}
                 order by (select count(*) from content_likes l where l.post_id = p.id)
                        + (select count(*) from comments c
                            where c.post_id = p.id and c.deleted_at is null) * 5 desc,
                          p.id desc
                 limit :lim offset :off
                """;
    }

    /**
     * 口径B：**不筛发布时间**，只计区间内新增的赞/评。
     *
     * <p>🔴 <b>{@code having} 那一条不能省</b>：不加的话，区间内零互动的老帖会以 0 分挤满榜单，
     * 而这个口径要看的恰恰是"这段时间谁被互动了"。
     */
    private static String platformActivitySql() {
        return """
                select p.id, p.type, p.author_id, left(coalesce(p.text, ''), 60), p.created_at,
                       (select count(*) from content_likes l
                         where l.post_id = p.id and l.created_at >= :from and l.created_at < :to) as likes,
                       (select count(*) from comments c
                         where c.post_id = p.id and c.deleted_at is null
                           and c.created_at >= :from and c.created_at < :to) as comments
                  from content_posts p
                 where """ + PUBLIC_FILTER + """
                   and ((select count(*) from content_likes l
                          where l.post_id = p.id and l.created_at >= :from and l.created_at < :to) > 0
                     or (select count(*) from comments c
                          where c.post_id = p.id and c.deleted_at is null
                            and c.created_at >= :from and c.created_at < :to) > 0)
                   {authorFilter}
                 order by (select count(*) from content_likes l
                            where l.post_id = p.id and l.created_at >= :from and l.created_at < :to)
                        + (select count(*) from comments c
                            where c.post_id = p.id and c.deleted_at is null
                              and c.created_at >= :from and c.created_at < :to) * 5 desc,
                          p.id desc
                 limit :lim offset :off
                """;
    }

    /**
     * 原生查询回来的时间戳。
     *
     * <p>⚠️ <b>驱动可能给 {@code Instant} 也可能给 {@code java.sql.Timestamp}</b> ——
     * 写死成后者会在运行期抛 ClassCastException（第一次跑就撞上了：
     * {@code timestamptz} 在当前驱动下回的是 {@code Instant}）。
     * 原生查询没有类型检查，这类错**编译期一点征兆都没有**，所以这里两种都接。
     */
    private static Instant toInstant(Object raw) {
        if (raw instanceof Instant i) {
            return i;
        }
        if (raw instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (raw instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        throw new IllegalStateException("无法识别的时间戳类型：" + raw.getClass());
    }

    /** CSV 单元格：逗号/引号/换行都要包起来，否则一条带逗号的正文会把整行列数错开。 */
    private static String csvCell(String raw) {
        String v = raw == null ? "" : raw.replace("\"", "\"\"").replace('\n', ' ');
        return '"' + v + '"';
    }
}
