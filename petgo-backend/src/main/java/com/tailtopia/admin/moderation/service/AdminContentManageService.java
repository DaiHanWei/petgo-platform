package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.dto.AdminContentRow;
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

    private static final int PAGE_SIZE = 50;

    private final ContentService contentService;
    private final AdminAuditService auditService;
    private final ReportService reportService;
    private final ViolationCountService violationCountService;
    // V1.1.6 Story 14.1：物种推导 + 「谁的内容运营可以改」的判据。
    private final ContentSpeciesResolver speciesResolver;
    private final com.tailtopia.auth.repository.UserRepository usersRepo;
    private final com.tailtopia.content.repository.ContentLikeRepository likes;
    private final com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;

    public AdminContentManageService(ContentService contentService, AdminAuditService auditService,
            ReportService reportService, ViolationCountService violationCountService,
            ContentSpeciesResolver speciesResolver,
            com.tailtopia.auth.repository.UserRepository usersRepo,
            com.tailtopia.content.repository.ContentLikeRepository likes,
            com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities) {
        this.contentService = contentService;
        this.auditService = auditService;
        this.reportService = reportService;
        this.violationCountService = violationCountService;
        this.speciesResolver = speciesResolver;
        this.usersRepo = usersRepo;
        this.likes = likes;
        this.identities = identities;
    }

    /** 全量浏览/筛选/搜索。status: ONLINE / DELETED / null=全部；type/authorId/q 任一空忽略。 */
    @Transactional(readOnly = true)
    public List<AdminContentRow> browse(String type, Long authorId, LocalDate from, LocalDate to,
            String status, String q, int page) {
        ContentType ct = parseType(type);
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String keyword = (q == null || q.isBlank()) ? null : q;
        return contentService.adminSearch(ct, authorId, fromI, toI, deleted, keyword,
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
        List<AdminContentRow> rows = browse(type, authorId, from, to, status, q, page);
        // 🛡 **整页一次算完**（resolveAll）—— 逐行 resolve 是 N+1，而这是运营最常打开的一页。
        var resolved = speciesResolver.resolveAll(rows.stream()
                .map(r -> new ContentSpeciesResolver.Input(r.id(), r.speciesOverride(), r.authorId()))
                .toList());
        // 可编辑判据用到的账号集合也一次查完。
        Set<Long> editableAuthors = editableAuthorIds(rows);
        return rows.stream()
                .map(r -> new ContentSpeciesRow(r,
                        resolved.getOrDefault(r.id(), ResolvedSpecies.NONE),
                        r.authorId() != null && editableAuthors.contains(r.authorId())))
                .filter(row -> species == null || species.isBlank()
                        || species.equals(row.species().species()))
                .filter(row -> speciesSource == null || speciesSource.isBlank()
                        || speciesSource.equals(row.source().name()))
                .toList();
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
            throw AppException.validation("物种取值须是 " + ContentSpecies.ALL);
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
            LocalDate to, String status, String q) {
        ContentType ct = parseType(type);
        Boolean deleted = "DELETED".equals(status) ? Boolean.TRUE
                : ("ONLINE".equals(status) ? Boolean.FALSE : null);
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String keyword = (q == null || q.isBlank()) ? null : q;

        // 多取一行用来判断「是不是还有更多」——不额外发一次 count。
        List<AdminContentRow> rows = contentService.adminSearch(ct, authorId, fromI, toI, deleted,
                keyword, EXPORT_MAX_ROWS + 1, 0);
        boolean truncated = rows.size() > EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, EXPORT_MAX_ROWS);
        }
        java.util.Map<Long, Long> likes = likeCounts(rows.stream().map(AdminContentRow::id).toList());

        StringBuilder csv = new StringBuilder(
                "post_id,type,author_id,likes,created_at_wib,status,text\n");
        for (AdminContentRow r : rows) {
            csv.append(r.id()).append(',')
                    .append(r.type() == null ? "" : r.type().name()).append(',')
                    .append(r.authorId() == null ? "" : r.authorId()).append(',')
                    .append(likes.getOrDefault(r.id(), 0L)).append(',')
                    // 🔴 导出的时间一律 WIB —— 后台全站按雅加达解释，
                    //    导出若给 UTC，运营会把两份对不上的数拿去做汇报。
                    .append(WIB_CSV.format(r.createdAt().atZone(WIB))).append(',')
                    .append(r.deleted() ? "DELETED" : "ONLINE").append(',')
                    .append(csvCell(r.textPreview())).append('\n');
        }
        if (truncated) {
            csv.append("# 已达单次导出上限 ").append(EXPORT_MAX_ROWS)
                    .append(" 行，请收窄时间范围后分批导出\n");
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

    /** 逗号/引号/换行都要转义，否则一条正文里的逗号就能把整份表的列错开。 */
    private static String csvCell(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        return '"' + raw.replace("\"", "\"\"") + '"';
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
