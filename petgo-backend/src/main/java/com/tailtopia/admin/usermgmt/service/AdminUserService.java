package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.admin.usermgmt.domain.DeletionType;
import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.admin.usermgmt.dto.AdminUserRow;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.AuthService;
import com.tailtopia.consult.service.ConsultHistoryService;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台用户只读聚合（Story 3.1，AB-UA-01）。**纯只读**：搜索 + 详情五块。跨模块**一律经各 owning service**
 * （auth/profile/content/consult），禁直读其 repository、禁跨库 join。问诊**仅元数据**，绝不读 IM 正文/AI 上下文/媒体。
 */
@Service
public class AdminUserService {

    private final AccountQueryService accountQuery;
    private final ProfileService profileService;
    private final ContentService contentService;
    private final ConsultHistoryService consultHistory;
    private final AuthService authService;
    private final ConsultInterruptService consultInterrupt;
    private final AdminAuditService auditService;
    private final AccountDeletionService accountDeletionService;
    private final PawCoinWalletService pawCoinWallet;
    /**
     * 仅供手机号筛选与召回名单导出（Story 11.4）。
     *
     * <p>⚠️ 其余读取一律走 {@code accountQuery} —— 本类不直接查 users 表是既有约定；
     * 这里破例是因为「按 phone 是否为空筛选 + 分页」必须写在 SQL 的 WHERE 里
     * （捞出来再筛会破坏分页），而 AccountQueryService 不该为一个后台专用筛选条件开口。
     */
    private final UserRepository users;
    /** 摘要条的单条聚合（Story 8.1）。 */
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    /** 导出表头与状态文案随当前 locale（Story 8.1 走 AdminExportWriter 之后）。 */
    private final com.tailtopia.shared.i18n.Messages msg;

    public AdminUserService(AccountQueryService accountQuery, ProfileService profileService,
            ContentService contentService, ConsultHistoryService consultHistory,
            AuthService authService, ConsultInterruptService consultInterrupt,
            AdminAuditService auditService, AccountDeletionService accountDeletionService,
            PawCoinWalletService pawCoinWallet, UserRepository users,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
            com.tailtopia.shared.i18n.Messages msg) {
        this.accountQuery = accountQuery;
        this.profileService = profileService;
        this.contentService = contentService;
        this.consultHistory = consultHistory;
        this.authService = authService;
        this.consultInterrupt = consultInterrupt;
        this.auditService = auditService;
        this.accountDeletionService = accountDeletionService;
        this.pawCoinWallet = pawCoinWallet;
        this.users = users;
        this.jdbc = jdbc;
        this.msg = msg;
    }

    /**
     * 后台赠送 PawCoin（bug 20260728-389）。经 owning service {@link PawCoinWalletService#credit} 以
     * {@code BONUS} 类型入账（钱包/总账/流水三写原子，计 PLATFORM_REVENUE 科目，对账不破坏）；幂等键取页面
     * 渲染时生成的一次性 token（防双击/刷新重复入账）；同事务写审计 PAWCOIN_GRANTED（含数量/原因）。
     */
    @Transactional
    public void grantPawCoin(long userId, long coins, String reason, String idempotencyToken,
            long actorAccountId) {
        if (coins <= 0) {
            throw AppException.validation("赠送数量必须为正整数").code("admin.err.user.grantAmountPositive");
        }
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("赠送原因不能为空").code("admin.err.user.grantReasonRequired");
        }
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.user.missingIdempotencyKey");
        }
        User target = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，不可赠送").code("admin.err.user.deletedNoGrant");
        }
        String idempotencyKey = "admin-grant:" + idempotencyToken.trim();
        pawCoinWallet.credit(userId, coins, PawCoinTxnType.BONUS, "ADMIN_GRANT", actorAccountId,
                idempotencyKey);
        auditService.record(actorAccountId, AuditActions.PAWCOIN_GRANTED, "USER",
                String.valueOf(userId),
                "赠送 PawCoin（数量：" + coins + "；原因：" + reason.trim() + "；幂等键：" + idempotencyKey + "）");
    }

    /**
     * 删除用户（Story 3.3，AB-UA-03，不可逆）。类型 + 备注必填。先写 USER_DELETED 审计（永久记录，含类型/备注/操作人）；
     * D2(VIOLATION) 前置下架该用户全部内容；最终复用既有 7.3 {@link AccountDeletionService#requestDeletion}
     * 触发级联（用户行物理删 → UGC 经 AuthorView 自动匿名 + 档案/名片删 + 问诊匿名 + 个人图/IM 媒体删）。
     * **不改 7.3 编排/表**；类型分支在本编排层。
     */
    @Transactional
    public void deleteUser(long userId, DeletionType type, String note, long actorAccountId) {
        if (type == null) {
            throw AppException.validation("请选择删除类型（注销 / 违规）").code("admin.err.user.deleteTypeRequired");
        }
        if (note == null || note.isBlank()) {
            throw AppException.validation("删除备注不能为空").code("admin.err.user.deleteNoteRequired");
        }
        User target = accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        // 已注销账号仅展示，禁止重复删除（否则重写审计 + 重触发级联）。
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，无需重复删除").code("admin.err.user.alreadyDeleted");
        }

        // 永久记录（append-only）：类型 + 备注 + 操作人；不落 PII。
        auditService.record(actorAccountId, AuditActions.USER_DELETED, "USER", String.valueOf(userId),
                "删除用户（类型：" + type + "；备注：" + note.trim() + "）");

        // D2：先下架全部内容（先下架后注销最稳，避免作者删后漏下架）。
        if (type == DeletionType.VIOLATION) {
            contentService.takedownAllByAuthor(userId);
        }
        // D1/D2 共用：触发既有级联注销（幂等 + 状态机 + 失败重扫）。
        accountDeletionService.requestDeletion(userId);
    }

    /**
     * 停用用户（Story 3.2，AC1/AC2/AC4）：①经 auth service 置 DEACTIVATED + 撤 refresh（即时不可登录/刷新）
     * ②经 consult service 强关进行中会话 ③同事务写审计 USER_DEACTIVATED。原因必填。
     */
    @Transactional
    public void deactivate(long userId, String reason, long actorAccountId) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("停用原因不能为空").code("admin.err.user.deactivateReasonRequired");
        }
        // 仅普通用户。
        accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        authService.deactivateUser(userId);
        consultInterrupt.interruptByUser(userId);
        auditService.record(actorAccountId, AuditActions.USER_DEACTIVATED, "USER",
                String.valueOf(userId), "停用用户（原因：" + reason.trim() + "）");
    }

    /** 重新激活用户（Story 3.2，AC5）：恢复登录权 + 写审计 USER_REACTIVATED。 */
    @Transactional
    public void reactivate(long userId, long actorAccountId) {
        accountQuery.findUserById(userId).orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        authService.reactivateUser(userId);
        auditService.record(actorAccountId, AuditActions.USER_REACTIVATED, "USER",
                String.valueOf(userId), "重新激活用户");
    }

    /** bug 20260701-164：后台用户管理分页列出全部普通用户（id 倒序，最近注册在前），供列表浏览。 */
    /** 摘要条四格（V1.3.0 Story 8.1 · AC1）：总用户数 · 今日新增（WIB）· 已停用 · 已删除。 */
    public record UserSummary(long total, long todayNew, long deactivated, long deleted) {
    }

    /** 🛡 与 11-1/11-2/11-3 四处一致：后台的「今天」按 WIB 算，不按服务器时区。 */
    private static final java.time.ZoneId WIB = java.time.ZoneId.of("Asia/Jakarta");

    /** 状态筛选的三个取值（Story 8.1 · AC1）；其余一律当「全部」。 */
    private static final java.util.Set<String> STATUS_MODES =
            java.util.Set.of("active", "deactivated", "deleted");

    /** 状态筛选归一：认得的原样返回，认不得的（含 null / 空 / 旧书签乱传）一律当「全部」。 */
    private static String statusMode(String status) {
        return status != null && STATUS_MODES.contains(status) ? status : null;
    }

    /**
     * 摘要条聚合（Story 8.1 · AC1）。
     *
     * <p>🔴 <b>一条 SQL 出四个数</b>，不是查四遍：四格必须来自同一个快照，
     * 分四次查的话「总数」与「已停用」可能取自两个时刻，运营会看到加不起来的数。
     *
     * <p>⚠️ 口径与列表的筛选**逐条对齐**（关键词 + 手机号 + 状态），否则摘要条说的是另一批人。
     * 关键词与 {@link #search} <b>逐条同判据</b>：
     * <ul>
     *   <li>全数字 → 按 id 精确；否则按注册邮箱精确（忽略大小写）。这两路**含已注销账号**
     *       （注销后仍可凭 id / 邮箱定位，见 {@code searchByDisplayedNameOrEmail} 的注释）。</li>
     *   <li>模糊路 → {@code coalesce(nickname, display_name)} 或邮箱 like，且**排除已注销**
     *       （昵称/邮箱随注销匿名化，拿来搜等于把匿名化又开一条缝）。</li>
     * </ul>
     * ⚠️ 曾经写成「nickname ILIKE 或 display_name ILIKE」且不排注销 —— 那是**比列表更宽**的网，
     * 于是搜索态下摘要条报的数常年大于底下能看到的行数。
     *
     * <p>⚠️ 唯一剩下的口径差：{@code search} 模糊路封顶 {@value #NAME_SEARCH_LIMIT} 条，
     * 摘要条数的是命中总数 —— 命中超过 50 时「总用户数」会大于列表行数（这是有意的：
     * 那个数正是在告诉运营「还有更多，缩小关键词」）。
     *
     * <p>🔴 可空参数一律给显式 SQL 类型：绑 null 时 Postgres 推不出类型（42P18），
     * 而「不带筛选」正是页面首次加载的那一次。
     */
    @Transactional(readOnly = true)
    public UserSummary summary(String q, String phoneMode, String status) {
        String keyword = (q == null || q.isBlank()) ? null : q.trim();
        String mode = ("filled".equals(phoneMode) || "empty".equals(phoneMode)) ? phoneMode : null;
        boolean qIsId = keyword != null && keyword.chars().allMatch(Character::isDigit);
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("q", keyword, java.sql.Types.VARCHAR)
                .addValue("qIsId", qIsId, java.sql.Types.BOOLEAN)
                .addValue("qLike", keyword == null ? null : "%" + keyword.toLowerCase() + "%",
                        java.sql.Types.VARCHAR)
                .addValue("phoneMode", mode, java.sql.Types.VARCHAR)
                .addValue("status", statusMode(status), java.sql.Types.VARCHAR)
                .addValue("today", java.time.LocalDate.now(WIB));
        return jdbc.query(SUMMARY_SQL, params, rs -> rs.next()
                ? new UserSummary(rs.getLong("total"), rs.getLong("today_new"),
                        rs.getLong("deactivated"), rs.getLong("deleted"))
                : new UserSummary(0, 0, 0, 0));
    }

    private static final String SUMMARY_SQL = """
            SELECT COUNT(*)                                                                       AS total,
                   COUNT(*) FILTER (WHERE (u.created_at AT TIME ZONE 'Asia/Jakarta')::date = :today) AS today_new,
                   COUNT(*) FILTER (WHERE u.status = 'DEACTIVATED' AND u.deleted_at IS NULL)      AS deactivated,
                   COUNT(*) FILTER (WHERE u.deleted_at IS NOT NULL)                               AS deleted
            FROM users u
            WHERE u.role = 'USER'
              AND (:phoneMode IS NULL
                   OR (:phoneMode = 'filled' AND u.phone IS NOT NULL AND u.phone <> '')
                   OR (:phoneMode = 'empty' AND (u.phone IS NULL OR u.phone = '')))
              AND (:status IS NULL
                   OR (:status = 'deleted' AND u.deleted_at IS NOT NULL)
                   OR (:status = 'deactivated' AND u.deleted_at IS NULL AND u.status = 'DEACTIVATED')
                   OR (:status = 'active' AND u.deleted_at IS NULL AND u.status <> 'DEACTIVATED'))
              AND (:q IS NULL
                   OR (:qIsId AND CAST(u.id AS text) = :q)
                   OR (NOT :qIsId AND lower(u.email) = lower(:q))
                   OR (u.deleted_at IS NULL
                       AND (lower(COALESCE(u.nickname, u.display_name)) LIKE :qLike
                            OR lower(COALESCE(u.email, '')) LIKE :qLike)))
            """;

    /** 单行（抽屉里处置成功后 oob 换掉列表里的那一行，Story 8.1）；不存在 → 404。 */
    @Transactional(readOnly = true)
    public AdminUserRow row(long userId) {
        return toRow(accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound")));
    }

    /**
     * 浏览态列表（bug 20260701-164 起；V1.3.0 Story 8.1 合并手机号与状态两个筛选）。
     * id 倒序 = 最近注册在前。
     *
     * <p>⚠️ 两个筛选必须在 <b>SQL 的 WHERE 里</b>：捞出来再筛会把分页算错
     * （每页少几行、总数还是全量，运营翻到第三页会看见空页）。
     */
    @Transactional(readOnly = true)
    public Page<AdminUserRow> listFiltered(String phoneMode, String status, Pageable pageable) {
        String phone = ("filled".equals(phoneMode) || "empty".equals(phoneMode)) ? phoneMode : "any";
        String st = statusMode(status) == null ? "all" : statusMode(status);
        return users.findAdminUsers(com.tailtopia.auth.domain.Role.USER, phone, st,
                UserStatus.DEACTIVATED, pageable).map(this::toRow);
    }

    /**
     * 搜索结果按手机号填写态 + 账号状态过滤（Story 8.1 · AC1）。
     *
     * <p>🔴 <b>三个筛选合进同一个 form 之后，搜索态必须把另外两个也筛上。</b>
     * 旧版手机号与关键词是**两个独立 form**（选手机号会丢掉 q），这条路几乎走不到；
     * 现在它是日常路径 —— 只筛关键词的话，屏幕上列的是全部命中，
     * 而摘要条（走 SQL，三个条件都带）数的是筛过的那批，两个数当场对不上。
     *
     * <p>⚠️ 搜索态**在内存里筛**是刻意的：{@link #search} 本来就不分页、封顶
     * {@value #NAME_SEARCH_LIMIT} 条，这里再筛不会影响分页正确性；
     * 而把这两个条件塞进 {@code search} 会改到 {@code AccountQueryService} 的通用搜索口径。
     */
    private static List<AdminUserRow> filterRows(List<AdminUserRow> rows, String phoneMode,
            String status) {
        String st = statusMode(status);
        boolean phoneFilled = "filled".equals(phoneMode);
        boolean phoneFilter = phoneFilled || "empty".equals(phoneMode);
        if (st == null && !phoneFilter) {
            return rows;
        }
        return rows.stream()
                .filter(r -> !phoneFilter || r.phoneFilled() == phoneFilled)
                .filter(r -> st == null || switch (st) {
                    case "deleted" -> r.deleted();
                    case "deactivated" -> !r.deleted() && r.deactivated();
                    default -> !r.deleted() && !r.deactivated();
                })
                .toList();
    }

    /** 昵称模糊命中上限：与列表页一页 50 条同量级，防「搜一个字」拖全表进内存。 */
    private static final int NAME_SEARCH_LIMIT = 50;

    /**
     * 按用户 id / 注册邮箱 / 昵称搜索普通用户（USER）。id 精确命中、完整邮箱精确命中排最前；
     * 昵称与邮箱都支持模糊匹配（2026-09-02 运营诉求：手里常常只有截图上的昵称或邮箱的一段），
     * 近注册在前、至多 {@value #NAME_SEARCH_LIMIT} 条。两路按 id 去重。
     */
    @Transactional(readOnly = true)
    public List<AdminUserRow> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        Optional<User> exact = q.chars().allMatch(Character::isDigit)
                ? safeById(q)
                : accountQuery.findUserByEmail(q);
        java.util.LinkedHashMap<Long, User> merged = new java.util.LinkedHashMap<>();
        exact.ifPresent(u -> merged.put(u.getId(), u));
        accountQuery.searchUsersByDisplayedName(q, NAME_SEARCH_LIMIT)
                .forEach(u -> merged.putIfAbsent(u.getId(), u));
        return merged.values().stream().map(this::toRow).toList();
    }

    /**
     * 搜索 + 手机号 / 状态筛选（Story 8.1 · AC1）：后两个在内存里筛，见 {@link #filterRows}。
     * 口径与 {@link #summary} 逐条一致 —— 摘要条与列表必须说同一批人。
     */
    @Transactional(readOnly = true)
    public List<AdminUserRow> search(String query, String phoneMode, String status) {
        return filterRows(search(query), phoneMode, status);
    }

    /**
     * 召回名单导出（Story 11.4）。
     *
     * <p>🛡 **不自动剔除已封号账号，但每行必须标注账号状态** —— 运营有时确实要联系已封号用户，
     * 但不标注就等于让他在不知情的情况下发召回。
     *
     * <p>🔴 **导出记审计**（PRD 未要求，本 story 加的）：PII 批量出库不留痕，
     * 事后无从回答"这份名单是谁什么时候导的"。
     *
     * @return CSV 文本（首行表头）
     */
    // ⚠️ **不能标 readOnly** —— 本方法要写审计行。第一版写成了 readOnly=true，
    //    结果导出直接 500（`cannot execute INSERT in a read-only transaction`）：
    //    读的部分没问题，是那条审计插入被只读事务挡了。
    @Transactional
    public byte[] exportRecallList(long actorAccountId, boolean filled) {
        List<User> rows = users.findAllByRoleAndPhoneFilled(
                com.tailtopia.auth.domain.Role.USER, filled);
        // bug 20260901-469 附带诉求：导出是真 .xlsx（原 CSV 在运营的 Excel 里挤成一列）。
        // V1.3.0 Story 8.1：改经 AdminExportWriter（2.3a 起全站导出一个出口，表头随 locale）。
        // ⚠️ 手机号一律**文本单元格**：印尼号码以 0 开头，数字单元格会把前导 0 吃掉，
        //    导出的名单就是一份拨不通的号码表 —— AdminExportWriter 只对 Number 落数字格，
        //    这里传的是 String，所以前导 0 保得住（这一条不能改成传 Long）。
        List<String> headers = List.of(
                msg.get("admin.v130.users.export.userId"),
                msg.get("admin.v130.users.export.displayName"),
                msg.get("admin.v130.users.export.phone"),
                msg.get("admin.v130.users.export.status"));
        List<List<Object>> data = new java.util.ArrayList<>();
        for (User u : rows) {
            boolean deleted = u.getDeletedAt() != null;
            String name = deleted ? u.getDeletedDisplayName() : currentName(u);
            // 账号状态：正常 / 已停用 / 已注销 —— 由运营自行判断是否纳入触达。
            String status = deleted ? msg.get("admin.users.status.deleted")
                    : (deactivated(u) ? msg.get("admin.users.status.deactivated")
                            : msg.get("admin.users.status.active"));
            data.add(java.util.Arrays.asList(
                    String.valueOf(u.getId()), name == null ? "" : name,
                    u.getPhone() == null ? "" : u.getPhone(), status));
        }
        byte[] body = com.tailtopia.admin.shared.export.AdminExportWriter.xlsx("recall", headers, data);
        // ⚠️ 审计摘要里**只写条数与筛选条件，绝不写号码本身**。
        auditService.record(actorAccountId, "USER_PHONE_RECALL_EXPORT", "USER", null,
                "导出召回名单：filter=" + (filled ? "已填写" : "未填写") + " rows=" + rows.size());
        return body;
    }

    /** 用户详情聚合（五块只读）。 */
    @Transactional(readOnly = true)
    public AdminUserDetailView detail(long userId) {
        return detail(userId, false);
    }

    /**
     * 用户详情聚合。
     *
     * @param includePhone 🛡 是否装入手机号。**false 时字段恒为 null，服务端就不下发** ——
     *                     只在模板里隐藏是不够的：数据已经到了浏览器，看源码就能拿到。
     */
    @Transactional(readOnly = true)
    public AdminUserDetailView detail(long userId, boolean includePhone) {
        User u = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));

        List<AdminUserDetailView.PetRow> pets = profileService.findByOwnerId(userId)
                .map(AdminUserService::toPetRow)
                .map(List::of)
                .orElseGet(List::of);

        boolean deleted = u.getDeletedAt() != null;
        // 已注销：显示名/邮箱取注销前快照列（仅后台展示）；未注销：昵称优先（同 toRow）。
        String name = deleted ? u.getDeletedDisplayName() : currentName(u);
        String email = deleted ? u.getDeletedEmail() : u.getEmail();
        return new AdminUserDetailView(
                u.getId(), name, u.getNickname(), email, u.getCreatedAt(),
                deactivated(u), deleted,
                includePhone ? u.getPhone() : null,
                pawCoinWallet.balanceOf(userId), pets,
                contentService.listByAuthorForAdmin(userId),
                consultHistory.adminSessionMetadata(userId));
    }

    private Optional<User> safeById(String digits) {
        try {
            return accountQuery.findUserById(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private AdminUserRow toRow(User u) {
        boolean deleted = u.getDeletedAt() != null;
        // 已注销：读注销前快照列展示「谁注销了」；未注销：昵称优先（用户改名落 nickname，
        // display_name 是注册时刻快照，与 AccountQueryService.toAuthorView 同一兜底约定）。
        String name = deleted ? u.getDeletedDisplayName() : currentName(u);
        String email = deleted ? u.getDeletedEmail() : u.getEmail();
        // 🛡 列表只带"有没有填"这个布尔，不带号码本身 —— 少一处出现 PII 就少一个泄漏面。
        boolean phoneFilled = u.getPhone() != null && !u.getPhone().isBlank();
        return new AdminUserRow(u.getId(), name, email, u.getCreatedAt(), deactivated(u), deleted,
                phoneFilled);
    }

    private static String currentName(User u) {
        return u.getNickname() != null ? u.getNickname() : u.getDisplayName();
    }

    private static AdminUserDetailView.PetRow toPetRow(PetProfile p) {
        return new AdminUserDetailView.PetRow(p.getId(), p.getName(),
                p.getPetType() == null ? null : p.getPetType().name(), p.getBreed(),
                // V1.3.0 Story 8.1：性别 / 生日纯读展示（PRD §5 ③ 第 2 条例外），不参与任何判定。
                p.getSex() == null ? null : p.getSex().name(), p.getBirthday());
    }

    /** Story 3.2：读用户状态。 */
    private boolean deactivated(User u) {
        return u.getStatus() == UserStatus.DEACTIVATED;
    }
}
