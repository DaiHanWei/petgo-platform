package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.admin.moderation.dto.ContentSpeciesRow;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.species.ContentSpecies;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.content.species.ResolvedSpecies;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.moderation.violation.service.ViolationCountService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台全量内容管理（Story 4.2，AB-3B）。**经 {@link ContentService}** 浏览/主动下架/恢复，禁 admin 直读 content repo。
 * 下架/恢复同事务写审计；下架必填原因（进审计 summary，不进作者通知）。安全攸关：勿埋绕过点。
 */
@Service
public class AdminContentManageService {

    /** 每页 20（V1.3.0 Story 7.1 · AC3，模板 B 列表口径；原为 50）。 */
    public static final int PAGE_SIZE = 20;

    private final ContentService contentService;
    private final AdminAuditService auditService;
    private final ReportService reportService;
    private final ViolationCountService violationCountService;
    // V1.1.6 Story 14.1：物种推导 + 「谁的内容运营可以改」的判据。
    private final ContentSpeciesResolver speciesResolver;
    private final com.tailtopia.auth.repository.UserRepository usersRepo;
    private final com.tailtopia.content.repository.ContentLikeRepository likes;
    private final com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;
    // 2026-08-31：浏览次数/人数列。经 content 模块的统计服务，不直读 view 表。
    private final com.tailtopia.content.service.ContentViewStatsService viewStats;

    // V1.3.0 Story 7.1：摘要条聚合（单条 SQL）、作者昵称批量投影、导出表头随界面语言。
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final com.tailtopia.auth.service.AccountQueryService accountQuery;
    private final com.tailtopia.shared.i18n.Messages msg;

    public AdminContentManageService(ContentService contentService, AdminAuditService auditService,
            ReportService reportService, ViolationCountService violationCountService,
            ContentSpeciesResolver speciesResolver,
            com.tailtopia.auth.repository.UserRepository usersRepo,
            com.tailtopia.content.repository.ContentLikeRepository likes,
            com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities,
            com.tailtopia.content.service.ContentViewStatsService viewStats,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
            com.tailtopia.auth.service.AccountQueryService accountQuery,
            com.tailtopia.shared.i18n.Messages msg) {
        this.jdbc = jdbc;
        this.accountQuery = accountQuery;
        this.msg = msg;
        this.contentService = contentService;
        this.auditService = auditService;
        this.reportService = reportService;
        this.violationCountService = violationCountService;
        this.speciesResolver = speciesResolver;
        this.usersRepo = usersRepo;
        this.likes = likes;
        this.identities = identities;
        this.viewStats = viewStats;
    }

    /** 全量浏览/筛选/搜索（默认创建时间倒序）。 */
    @Transactional(readOnly = true)
    public List<AdminContentRow> browse(String type, Long authorId, LocalDate from, LocalDate to,
            String status, String q, int page) {
        return browse(type, authorId, from, to, status, q, null, page);
    }

    /**
     * 全量浏览/筛选/搜索。status: ONLINE / DELETED / null=全部；type/authorId/q 任一空忽略；
     * sort: comments_desc / comments_asc 按评论数排，其余按创建时间倒序。
     */
    @Transactional(readOnly = true)
    public List<AdminContentRow> browse(String type, Long authorId, LocalDate from, LocalDate to,
            String status, String q, String sort, int page) {
        ContentType ct = parseType(type);
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String keyword = (q == null || q.isBlank()) ? null : q;
        String s = ("comments_desc".equals(sort) || "comments_asc".equals(sort)) ? sort : null;
        return contentService.adminSearch(ct, authorId, fromI, toI, deleted, keyword, s,
                PAGE_SIZE, Math.max(page, 0) * PAGE_SIZE);
    }

    /**
     * 带物种信息的浏览（V1.1.6 Story 14.1 · AC5）。
     *
     * <p>🔴 <b>这是存量种子内容唯一的物种修正入口</b>：触点 ②③ 都是**发布时才存在**的入口，
     * 已发布的内容回不去。没有这一处，运营就只剩"改账号定位"这一个<b>全量粗粒度开关</b>，
     * 无法修正个别错标。
     *
     * <p>本列另一个用途：让运营自查"还有多少内容没有物种归属"，<b>直接验证配置效果</b> ——
     * 否则配完账号定位无从确认结果。
     *
     * @param species       按最终物种筛（可空）
     * @param speciesSource 按**推导来源**筛（可空）。AC5 举的典型用法就是靠它：
     *                      把某号定位改成 CAT 后，用「来源=账号定位」+「物种=猫」
     *                      筛出被批量套上去的内容，再改掉其中实际是狗内容的那几条
     */
    @Transactional(readOnly = true)
    public List<ContentSpeciesRow> browseWithSpecies(String type, Long authorId, LocalDate from,
            LocalDate to, String status, String q, int page, String species, String speciesSource) {
        return browseWithSpecies(type, authorId, from, to, status, q, null, page,
                species, speciesSource);
    }

    /**
     * 带物种信息的浏览 + 排序（2026-08-28 合并 main 时补的透传）。
     *
     * <p>🔴 `sort` 必须一路传到查询层：内容管理页走的是**本方法**而不是 {@link #browse}，
     * 合并时若只在 browse 上接了 sort，「按评论数排序」在运营天天打开的这一页上是**死的** ——
     * 点表头有反应（URL 变了）、顺序不变，而这种"半生效"最难被发现。
     */
    @Transactional(readOnly = true)
    public List<ContentSpeciesRow> browseWithSpecies(String type, Long authorId, LocalDate from,
            LocalDate to, String status, String q, String sort, int page, String species,
            String speciesSource) {
        return browseWithSpeciesPage(type, authorId, from, to, status, q, sort, page, species,
                speciesSource).rows();
    }

    /**
     * 同上，外带「<b>数据库那一页取满了没有</b>」（V1.3.0 Story 7.1 分页用）。
     *
     * <p>🔴 物种是**取完一页之后在应用层过滤**的，所以「本页剩几行」<b>不能</b>用来判断有没有下一页：
     * 筛 CAT 时一页 20 行里可能只剩 3 行，按行数判就永远是「没有下一页」——
     * 运营只能看到前 20 条里恰好匹配的那几条，而且<b>看不出被截断</b>
     * （AC5 举的典型用法「筛出被批量套上 CAT 的内容再逐条改」到第 21 条就够不着了）。
     * 判据必须落在**过滤前**的行数上。
     */
    @Transactional(readOnly = true)
    public SpeciesPage browseWithSpeciesPage(String type, Long authorId, LocalDate from,
            LocalDate to, String status, String q, String sort, int page, String species,
            String speciesSource) {
        List<AdminContentRow> rows = browse(type, authorId, from, to, status, q, sort, page);
        boolean dbPageFull = rows.size() >= PAGE_SIZE;
        // 🛡 **整页一次算完**（resolveAll）—— 逐行 resolve 是 N+1，而这是运营最常打开的一页。
        var resolved = speciesResolver.resolveAll(rows.stream()
                .map(r -> new ContentSpeciesResolver.Input(r.id(), r.speciesOverride(), r.authorId()))
                .toList());
        // 可编辑判据用到的账号集合也一次查完。
        Set<Long> editableAuthors = editableAuthorIds(rows);
        return new SpeciesPage(rows.stream()
                .map(r -> new ContentSpeciesRow(r,
                        resolved.getOrDefault(r.id(), ResolvedSpecies.NONE),
                        r.authorId() != null && editableAuthors.contains(r.authorId())))
                .filter(row -> species == null || species.isBlank()
                        || species.equals(row.species().species()))
                .filter(row -> speciesSource == null || speciesSource.isBlank()
                        || speciesSource.equals(row.source().name()))
                .toList(), dbPageFull);
    }

    /**
     * 带物种信息的一页。
     *
     * @param dbPageFull 数据库那一页取满了 ⇒ <b>还有下一页</b>（与过滤后的 {@code rows.size()} 无关，
     *                   见 {@link #browseWithSpeciesPage} 的说明）
     */
    public record SpeciesPage(List<ContentSpeciesRow> rows, boolean dbPageFull) {
    }

    /**
     * 摘要条的 where —— ⚠️ 必须与列表查询（{@code adminSearch}）的条件<b>逐条对齐</b>，
     * 否则摘要上的数与表格里看到的行对不上，而这种对不上没人会怀疑是两处 where 写岔了。
     *
     * <p>可空参数一律 {@code (:x IS NULL OR …)} + 绑定时给显式 SQL 类型，规避 Postgres
     * 「无类型 null 参数无法推断类型」（42P18，这个仓库踩过两次）。
     */
    private static final String SUMMARY_WHERE = """
            (:type IS NULL OR p.type = :type)
              AND (:authorId IS NULL OR p.author_id = :authorId)
              AND (:fromTs IS NULL OR p.created_at >= :fromTs)
              AND (:toTs IS NULL OR p.created_at < :toTs)
              AND (:deleted IS NULL OR (:deleted = TRUE AND p.deleted_at IS NOT NULL)
                                    OR (:deleted = FALSE AND p.deleted_at IS NULL))
              AND (:q IS NULL OR p.text ILIKE :q)
            """;

    /** 摘要条五格一条聚合（AC2）。限流用 EXISTS 子查询：内容级或作者账号级，且当前生效（未解除、未到期）。 */
    private static final String SUMMARY_SQL = """
            SELECT COUNT(*)                                                                          AS total,
                   COUNT(*) FILTER (WHERE (p.created_at AT TIME ZONE 'Asia/Jakarta')::date = :today) AS today_new,
                   COUNT(*) FILTER (WHERE p.status = 'UNDER_REVIEW' AND p.deleted_at IS NULL)        AS under_review,
                   COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM rank_throttles t
                                                   WHERE t.lifted_at IS NULL
                                                     AND (t.expires_at IS NULL OR t.expires_at > now())
                                                     AND ((t.scope = 'POST' AND t.target_id = p.id)
                                                       OR (t.scope = 'ACCOUNT' AND t.target_id = p.author_id)))) AS throttled,
                   COUNT(*) FILTER (WHERE p.deleted_at IS NOT NULL)                                  AS taken_down
            FROM content_posts p
            WHERE
            """ + SUMMARY_WHERE; // ⚠️ 文本块会剥尾随空格：WHERE 必须单独一行，否则拼成 WHEREp.（5.2 复审踩过）

    /**
     * 摘要条（Story 7.1 · AC2）：总帖数 · 今日新增（WIB）· 审核中 · 限流中 · 已下架，随当前筛选联动。
     *
     * <p>⚠️ {@code dateBasis=liked} 时**不传日期区间**：那两个日期指的是<b>点赞发生的时间</b>，
     * 塞进 {@code created_at} 会给出一份和屏幕对不上的数（界面上另有一行说明这一档的口径）。
     *
     * <p>⚠️ 物种 / 推导来源两个筛选<b>不进摘要</b>：物种是应用层按作者档案推导后再过滤的，
     * SQL 里表达不出来。摘要因此是「不含物种筛选」的口径。
     */
    @Transactional(readOnly = true)
    public com.tailtopia.admin.moderation.dto.ContentSummary summary(String type, Long authorId,
            LocalDate from, LocalDate to, String status, String q, String dateBasis) {
        boolean liked = "liked".equals(dateBasis);
        Instant fromI = (liked || from == null) ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = (liked || to == null) ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        ContentType ct = parseType(type);
        String keyword = (q == null || q.isBlank()) ? null
                : "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("type", ct == null ? null : ct.name(), java.sql.Types.VARCHAR)
                .addValue("authorId", authorId, java.sql.Types.BIGINT)
                .addValue("fromTs", fromI == null ? null : java.sql.Timestamp.from(fromI), java.sql.Types.TIMESTAMP)
                .addValue("toTs", toI == null ? null : java.sql.Timestamp.from(toI), java.sql.Types.TIMESTAMP)
                .addValue("deleted", deleted, java.sql.Types.BOOLEAN)
                .addValue("q", keyword, java.sql.Types.VARCHAR)
                .addValue("today", LocalDate.now(WIB));
        return jdbc.query(SUMMARY_SQL, params, rs -> rs.next()
                ? new com.tailtopia.admin.moderation.dto.ContentSummary(rs.getLong("total"),
                        rs.getLong("today_new"), rs.getLong("under_review"), rs.getLong("throttled"),
                        rs.getLong("taken_down"))
                : new com.tailtopia.admin.moderation.dto.ContentSummary(0, 0, 0, 0, 0));
    }

    /**
     * 单条内容的物种归属行（Story 7.1 抽屉 / oob 行用）；内容不存在 → 404。
     *
     * <p>与列表同一套判据：物种由 {@link ContentSpeciesResolver} 推导，
     * {@code editable} 仍是「作者是虚拟账号或在运营发布身份池内」（真实用户内容只读）。
     */
    @Transactional(readOnly = true)
    public ContentSpeciesRow speciesRow(long postId) {
        AdminContentRow row = contentService.adminRow(postId);
        if (row == null) {
            throw AppException.notFound("内容不存在").code("admin.err.content.notFound");
        }
        var resolved = speciesResolver.resolveAll(List.of(
                new ContentSpeciesResolver.Input(row.id(), row.speciesOverride(), row.authorId())));
        Set<Long> editable = editableAuthorIds(List.of(row));
        return new ContentSpeciesRow(row, resolved.getOrDefault(row.id(), ResolvedSpecies.NONE),
                row.authorId() != null && editable.contains(row.authorId()));
    }

    /**
     * 本页作者的昵称 / 注销态投影（Story 7.1 · AC6：列表与抽屉作者列置灰 + #id）。
     *
     * <p>🔴 整页一次批量取，与物种推导、点赞数、限流状态同一条纪律。
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, com.tailtopia.auth.dto.AuthorView> authorViews(
            java.util.Collection<Long> userIds) {
        var ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        // ⚠️ 空集合也回 HashMap 而不是 Map.of()：模板里是 authors?.get(authorId)，
        //    Map.of() 对 null key 直接抛 NPE，两条路径的行为不该只在「这一页恰好没作者」时才不同。
        return ids.isEmpty() ? new java.util.HashMap<>() : accountQuery.findAuthorViews(ids);
    }

    /**
     * 哪些作者的内容运营可以改物种。
     *
     * <p>🛡 虚拟账号 + 运营发布身份池内的真实账号。**普通用户一律只读** ——
     * 他们的物种由自己的宠物档案决定，运营手工干预等于替用户改自己的档案结论。
     */
    private Set<Long> editableAuthorIds(List<AdminContentRow> rows) {
        List<Long> ids = rows.stream().map(AdminContentRow::authorId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> out = new java.util.HashSet<>();
        for (var u : usersRepo.findAllById(ids)) {
            if (identities.isInPool(u)) {
                out.add(u.getId());
            }
        }
        return out;
    }

    /**
     * 设置 / 清除行级物种覆写（AC5）。传 {@code null} 或空即清除。
     *
     * <p>🛡 <b>只允许改种子内容</b>：真实用户内容只读。
     * 这一层的校验是权威的 —— 界面上不给按钮只是体验，改个请求参数就能绕过。
     */
    @Transactional
    public int setSpeciesOverride(List<Long> postIds, String species, long actorAccountId) {
        String value = (species == null || species.isBlank()) ? null : species.trim();
        if (value != null && !ContentSpecies.isValid(value)) {
            throw AppException.validation("物种取值须是 " + ContentSpecies.ALL)
                    .code("admin.err.content.speciesInvalid", ContentSpecies.ALL);
        }
        int changed = 0;
        for (Long postId : postIds) {
            var row = contentService.adminRow(postId);
            if (row == null || row.authorId() == null) {
                continue;
            }
            var author = usersRepo.findById(row.authorId()).orElse(null);
            if (author == null || !identities.isInPool(author)) {
                // 🛡 真实用户内容只读 —— 静默跳过而不是抛错：批量操作里混进一条
                //    就把整批毙掉，运营还得自己找出是哪条。
                continue;
            }
            contentService.setSpeciesOverride(postId, value);
            changed++;
        }
        if (changed > 0) {
            auditService.record(actorAccountId, "CONTENT_SET_SPECIES", "content_post",
                    postIds.toString(), "species=" + (value == null ? "(清除)" : value)
                            + " changed=" + changed);
        }
        return changed;
    }

    /**
     * 「按点赞时间」口径一次最多纳入多少条内容。到顶时**在界面上明说**，绝不静默截断。
     *
     * <p>它先于其它筛选生效（先按窗口内赞数取前 N，再套类型/作者/关键词），
     * 所以到顶意味着「这段时间里有赞的内容超过 N 条」，收窄时间范围即可。
     */
    private static final int LIKE_WINDOW_POOL = 2000;

    /**
     * 「这段时间里产生了多少互动」（2026-08-28，取代互动积分页的口径B）。
     *
     * <p>🔴 与默认口径回答的是**两个不同的问题**，这正是它必须存在的理由：
     * <ul>
     *   <li>默认（按发布时间）：这段时间**发的**内容，质量如何 —— 赞数是至今累计。</li>
     *   <li>本口径（按点赞时间）：这段时间**产生了**多少互动 —— 一条三个月前的帖子
     *       这周被翻出来点了 50 个赞，只有它看得见。</li>
     * </ul>
     * 两者都对，但回答不了对方的问题。互动积分页原来就是靠这两档并存，撤页时一并丢了。
     *
     * <p>取数分两步而不是一条大 SQL：先按窗口内赞数取前 {@link #LIKE_WINDOW_POOL} 条，
     * 再在内存里套类型/作者/状态/关键词。⚠️ 这**不是**偷懒 —— 窗口内有赞的内容本就是
     * 全量里很小的一撮，为它拼一条带可空参数的原生 SQL 反而要处理 Postgres 的类型推断
     * （那个坑这个仓库已经踩过两次）。
     *
     * @return 已排好序（窗口内赞数降序）的行 + 窗口内赞数 + 是否到顶
     */
    @Transactional(readOnly = true)
    public LikeWindowPage browseByLikeWindow(String type, Long authorId, LocalDate from,
            LocalDate to, String status, String q, int page) {
        Instant fromI = (from == null ? LocalDate.now(WIB).minusDays(6) : from)
                .atStartOfDay(WIB).toInstant();
        Instant toI = (to == null ? LocalDate.now(WIB) : to).plusDays(1).atStartOfDay(WIB).toInstant();

        List<ContentLikeRepository.PostLikeCount> pool = likes.countInWindow(fromI, toI,
                org.springframework.data.domain.PageRequest.of(0, LIKE_WINDOW_POOL));
        boolean poolFull = pool.size() >= LIKE_WINDOW_POOL;
        if (pool.isEmpty()) {
            return new LikeWindowPage(List.of(), java.util.Map.of(), false);
        }

        java.util.LinkedHashMap<Long, Long> ordered = new java.util.LinkedHashMap<>();
        pool.forEach(c -> ordered.put(c.getPostId(), c.getLikeCount()));

        // 一次取回，再按 pool 的顺序还原 —— findAllById 不保证顺序。
        java.util.Map<Long, AdminContentRow> byId = contentService.adminRowsByIds(ordered.keySet())
                .stream().collect(java.util.stream.Collectors.toMap(AdminContentRow::id, r -> r, (a, b) -> a));

        ContentType ct = parseType(type);
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        String keyword = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        List<AdminContentRow> matched = ordered.keySet().stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .filter(r -> ct == null || r.type() == ct)
                .filter(r -> authorId == null || java.util.Objects.equals(r.authorId(), authorId))
                .filter(r -> deleted == null || r.deleted() == deleted)
                .filter(r -> keyword == null
                        || (r.textPreview() != null
                            && r.textPreview().toLowerCase().contains(keyword)))
                .toList();

        // page < 0 = **不分页**（导出用）。⚠️ 用一个显式的负数约定，
        // 不要拿 Integer.MIN_VALUE 之类去撞 Math.max —— 那会安静地退化成"第一页"，
        // 导出于是只带出 50 行而没有任何报错（第一版正是这么写的）。
        if (page < 0) {
            return new LikeWindowPage(matched, ordered, poolFull);
        }
        int offset = page * PAGE_SIZE;
        List<AdminContentRow> pageRows = offset >= matched.size() ? List.of()
                : matched.subList(offset, Math.min(offset + PAGE_SIZE, matched.size()));
        return new LikeWindowPage(pageRows, ordered, poolFull);
    }

    /**
     * 「按点赞时间」口径的一页。
     *
     * @param windowLikes postId → **窗口内**赞数（不是至今累计）
     * @param poolFull    候选池已到 {@link #LIKE_WINDOW_POOL} 上限 ⇒ 界面必须提示收窄时间范围。
     *                    🔴 不提示等于给出一份看不出被截断的报表。
     */
    public record LikeWindowPage(List<AdminContentRow> rows, java.util.Map<Long, Long> windowLikes,
            boolean poolFull) {
    }

    /** 导出一次最多带出多少行。到顶时**在文件尾和审计里都写明**，绝不静默截断。 */
    private static final int EXPORT_MAX_ROWS = 5000;

    /**
     * 按当前筛选条件导出 CSV（bug 20260828）。
     *
     * <p>「内容互动积分」整页撤掉后，导出能力随之没了。它是运营做经营汇报时唯一的出口，
     * 于是回到它本来该在的地方 —— **内容管理的筛选结果直接导出**，
     * 而不是再开一个只为导出而存在的页面。
     *
     * <p>🔴 **导出是把数据批量带出系统**，因此与列表查看分权限、且**记审计**
     * （操作人 / 时间 / 条数 / 筛选条件）—— 与召回名单导出同一条口径（Story 11.4）。
     *
     * <p>🔴 **不静默截断**：超过 {@link #EXPORT_MAX_ROWS} 时，文件末尾追加一行说明、
     * 审计摘要里也记 truncated。一份被悄悄砍掉一半的报表，比没有报表更糟 ——
     * 看的人不会知道自己看的是残缺数据。
     *
     * @return CSV 文本（首行表头，UTF-8 BOM 由控制器负责）
     */
    @Transactional
    public String exportCsv(long actorAccountId, String type, Long authorId, LocalDate from,
            LocalDate to, String status, String q, String dateBasis) {
        // 🔴 导出必须跟随屏幕上的**口径**（2026-08-28）：不跟随的话，运营在
        //    「按点赞时间」下点导出，拿到的却是一份按发布时间筛的表 —— 数字对不上，
        //    而两份表长得一模一样，他不会怀疑是口径不同。
        if ("liked".equals(dateBasis)) {
            return exportLikeWindowCsv(actorAccountId, type, authorId, from, to, status, q);
        }
        ContentType ct = parseType(type);
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String keyword = (q == null || q.isBlank()) ? null : q;

        // 多取一行用来判断「是不是还有更多」——不额外发一次 count。
        // sort 传 null = 创建时间倒序。导出不跟随表头排序：一份报表按"最近在前"最好读，
        // 而表头排序是屏幕上临时看的动作，带进文件反而让两次导出对不上。
        List<AdminContentRow> rows = contentService.adminSearch(ct, authorId, fromI, toI, deleted,
                keyword, null, EXPORT_MAX_ROWS + 1, 0);
        boolean truncated = rows.size() > EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, EXPORT_MAX_ROWS);
        }
        java.util.Map<Long, Long> likes = likeCounts(rows.stream().map(AdminContentRow::id).toList());
        var views = viewStats(rows.stream().map(AdminContentRow::id).toList());

        List<List<Object>> data = new java.util.ArrayList<>();
        for (AdminContentRow r : rows) {
            data.add(exportRow(r, likes.getOrDefault(r.id(), 0L), views.get(r.id())));
        }
        StringBuilder csv = new StringBuilder(
                com.tailtopia.admin.shared.export.AdminExportWriter.csv(exportHeaders(false), data));
        if (truncated) {
            // 说明行随界面语言（表头都本地化了，只有这一行是中文会显得像半成品）。
            csv.append("# ").append(msg.get("admin.v130.content.export.truncated", EXPORT_MAX_ROWS))
                    .append("\r\n");
        }
        auditService.record(actorAccountId, "CONTENT_LIST_EXPORT", "content_post", "-",
                "rows=" + rows.size() + " truncated=" + truncated
                        + " type=" + type + " authorId=" + authorId
                        + " from=" + from + " to=" + to + " status=" + status
                        + " q=" + (keyword == null ? "" : "(有关键词)"));
        return csv.toString();
    }

    private static final java.time.ZoneId WIB = java.time.ZoneId.of("Asia/Jakarta");
    private static final java.time.format.DateTimeFormatter WIB_CSV =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 导出表头（Story 7.1 · AC7）：随界面语言，key 沿用列表页的 {@code admin.content.col.*}。
     *
     * @param likedWindow 「按点赞时间」档 —— 赞数列是<b>窗口内</b>的，表头必须跟着换，
     *                    否则两份长得一样的表会被当成同一口径读
     */
    private List<String> exportHeaders(boolean likedWindow) {
        // ⚠️ 三列**不复用**列表页的表头文案：屏幕上「作者」那一格是昵称、「创建时间」旁边有 WIB 字样，
        //    而文件里这两格分别是**作者 id** 与**已折算成 WIB 的时间字符串**。沿用列表文案会把口径标记丢掉，
        //    而「导出的时间到底是哪个时区」正是这个方法上方那条 🔴 注释在防的事。
        return List.of(msg.get("admin.v130.content.export.col.postId"), msg.get("admin.content.col.type"),
                msg.get("admin.v130.content.export.col.authorId"),
                msg.get(likedWindow ? "admin.content.col.likesInWindow" : "admin.content.col.likes"),
                msg.get("admin.content.col.views"), msg.get("admin.content.col.viewers"),
                msg.get("admin.v130.content.export.col.createdWib"), msg.get("admin.content.col.status"),
                msg.get("admin.content.col.preview"));
    }

    /**
     * 一行导出数据（转义交给 {@code AdminExportWriter}：RFC 4180 + 前导 {@code =} 防公式注入）。
     *
     * <p>🔴 时间一律 WIB —— 后台全站按雅加达解释；导出若给 UTC，运营会把两份对不上的数拿去做汇报。
     */
    private static List<Object> exportRow(AdminContentRow r, long likeCount,
            com.tailtopia.content.service.ContentViewStatsService.ViewStat vs) {
        return java.util.Arrays.asList(r.id(), r.type() == null ? "" : r.type().name(),
                r.authorId() == null ? "" : r.authorId(), likeCount,
                vs == null ? 0 : vs.views(), vs == null ? 0 : vs.viewers(),
                WIB_CSV.format(r.createdAt().atZone(WIB)), r.deleted() ? "DELETED" : "ONLINE",
                r.textPreview());
    }

    /** 「按点赞时间」口径的导出。列头里的赞数是**窗口内**的，与屏幕一致。 */
    private String exportLikeWindowCsv(long actorAccountId, String type, Long authorId,
            LocalDate from, LocalDate to, String status, String q) {
        // page=-1 拿不到全部，这里直接把池子当成一页取：口径本身已按 LIKE_WINDOW_POOL 封顶。
        LikeWindowPage all = browseByLikeWindowAll(type, authorId, from, to, status, q);
        // ⚠️ 浏览两列是**至今累计**，不跟随「点赞时间」窗口 —— 浏览记录只存每人累计次数，
        //    没有逐次时间线，给不出「窗口内的浏览」。列名不带 in_range，就是为了不被误读成窗口值。
        var views = viewStats(all.rows().stream().map(AdminContentRow::id).toList());
        List<List<Object>> data = new java.util.ArrayList<>();
        for (AdminContentRow r : all.rows()) {
            data.add(exportRow(r, all.windowLikes().getOrDefault(r.id(), 0L), views.get(r.id())));
        }
        StringBuilder csv = new StringBuilder(
                com.tailtopia.admin.shared.export.AdminExportWriter.csv(exportHeaders(true), data));
        if (all.poolFull()) {
            csv.append("# ").append(msg.get("admin.v130.content.export.poolFull", LIKE_WINDOW_POOL))
                    .append("\r\n");
        }
        auditService.record(actorAccountId, "CONTENT_LIST_EXPORT", "content_post", "-",
                "basis=liked rows=" + all.rows().size() + " truncated=" + all.poolFull()
                        + " from=" + from + " to=" + to);
        return csv.toString();
    }

    /** 同 {@link #browseByLikeWindow} 但**不分页**（导出用）。page 传 -1 即全部。 */
    @Transactional(readOnly = true)
    public LikeWindowPage browseByLikeWindowAll(String type, Long authorId, LocalDate from,
            LocalDate to, String status, String q) {
        return browseByLikeWindow(type, authorId, from, to, status, q, -1);
    }

    /**
     * 本页内容各自的点赞数（bug 20260828）。
     *
     * <p>产品把「内容互动积分」那一整页撤掉了，改为在内容管理里直接看点赞数 ——
     * 运营真正常用的只有这一个数，为它单开一页（还带两套统计口径与 CSV 导出）
     * 是把一个小问题做成了一个要先学会怎么用的工具。
     *
     * <p>🔴 **整页一次批量取**，与物种推导、限流状态同一条纪律：
     * 逐行 count 就是 N+1，一页 50 行就是 50 次查询。
     *
     * <p>⚠️ 返回的 Map **不包含零赞的帖子**（SQL 的 group by 不会给空组造行），
     * 模板取值时必须自己兜底成 0，不能直接打印 null。
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Long> likeCounts(java.util.Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return java.util.Map.of();
        }
        return likes.countByPostIdIn(postIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.tailtopia.content.repository.ContentLikeRepository.PostLikeCount::getPostId,
                        com.tailtopia.content.repository.ContentLikeRepository.PostLikeCount::getLikeCount));
    }


    /**
     * 本页内容各自的浏览统计（次数 + 人数，2026-08-31）。
     *
     * <p>口径：打开详情页记一次，作者本人不计（写入侧的规则，见
     * {@code ContentViewStatsService}）。🔴 整页一次批量取，与点赞数同一条纪律。
     * ⚠️ 没被看过的帖不在 Map 里，模板自己兜底成 0。
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, com.tailtopia.content.service.ContentViewStatsService.ViewStat>
            viewStats(java.util.Collection<Long> postIds) {
        return viewStats.statsFor(postIds);
    }

    /** 按 id 取单条后台行（HTMX 局部刷新用）；不存在返回 null。 */
    @Transactional(readOnly = true)
    public AdminContentRow row(long postId) {
        return contentService.adminRow(postId);
    }

    /** 主动下架（必填原因）：软删 + 关闭该帖待处理举报单 + 作者通知（既有事件）+ 审计。 */
    @Transactional
    public void takedown(long postId, String reason, long actorAccountId) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("下架原因不能为空").code("admin.err.moderation.reasonRequired");
        }
        // story 9 幂等（AC-8）：仅当帖当前【未删】时本次下架才是真实迁移 → 计一次。
        var summary = contentService.findSummary(postId);
        Long postAuthorId = summary.map(ContentService.PostSummary::authorId).orElse(null);
        boolean firstTakedown = summary.map(s -> !s.deleted()).orElse(false);
        contentService.softDelete(postId, DeleteReason.ADMIN_TAKEDOWN);
        // bug 20260630-155：内容管理主动下架时同步关闭该帖 PENDING 举报单，避免残留在举报待处理队列。
        reportService.resolvePendingForPost(postId, actorAccountId);
        auditService.record(actorAccountId, AuditActions.CONTENT_TAKEN_DOWN, "CONTENT_POST",
                String.valueOf(postId), "主动下架内容（原因：" + reason.trim() + "）");
        // story 9 §5.1：后台巡查下架 = 人工判定违规 → 同事务累加 POST 计数（仅真实下架，幂等）。
        if (postAuthorId != null && firstTakedown) {
            violationCountService.record(postAuthorId, ViolationType.POST);
        }
    }

    /** 恢复已下架内容 + 审计。 */
    @Transactional
    public void restore(long postId, long actorAccountId) {
        contentService.restore(postId);
        auditService.record(actorAccountId, AuditActions.CONTENT_RESTORED, "CONTENT_POST",
                String.valueOf(postId), "恢复已下架内容");
    }

    private ContentType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return ContentType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
