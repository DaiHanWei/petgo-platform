package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.dto.VirtualAccountRow;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.species.ContentSpecies;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台虚拟账号管理（Story 9.8，A-6）。建（无登录）/ 列表 / 启停 + 审计。虚拟账号复用 {@code users} 表 +
 * {@code content_posts.author_id} 发种子；合成 {@code google_sub}（{@code virtual:<uuid>}）满足非空唯一约束，
 * 无密码、无真实 google 身份 → 天然不可登录。
 */
@Service
public class AdminVirtualAccountService {

    private static final int NICKNAME_MAX = 20;

    private final UserRepository users;
    private final AdminAuditService audit;

    public AdminVirtualAccountService(UserRepository users, AdminAuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<VirtualAccountRow> list() {
        return list(null);
    }

    /**
     * 列表，可按账号物种定位筛选（V1.1.6 Story 14.1 · AC2）。
     *
     * <p>⚠️ 筛选在内存里做而不是写一条带条件的查询：虚拟账号是**运营手工建的**，
     * 数量级在几十到几百 —— 为它上一条带 NULL-as-GENERAL 语义的 SQL 反而更难读
     * （那个默认值只存在于读时）。
     */
    @Transactional(readOnly = true)
    public List<VirtualAccountRow> list(String speciesFilter) {
        return users.findByAccountTypeOrderByIdDesc(AccountType.VIRTUAL).stream()
                .map(AdminVirtualAccountService::toRow)
                .filter(r -> speciesFilter == null || speciesFilter.isBlank()
                        || speciesFilter.equals(r.accountSpecies()))
                .toList();
    }

    /** 建虚拟账号（无登录）。昵称必填 ≤20；头像选填。 */
    @Transactional
    public long create(String nickname, String avatarUrl, long adminId) {
        return create(nickname, avatarUrl, null, adminId);
    }

    /**
     * 建虚拟账号，带账号物种定位（V1.1.6 Story 14.1 · AC2）。
     *
     * <p>🔴 定位在界面上是**必填**，但这里对 null 宽容 —— 读时会按 {@code GENERAL} 解释。
     * 服务端硬拦会让既有的那个两参构造（以及一堆测试）全部失效，
     * 而它拦住的只是"运营漏选了一项、结果等于选了最常用的那个"。
     */
    @Transactional
    public long create(String nickname, String avatarUrl, String accountSpecies, long adminId) {
        String nn = nickname == null ? "" : nickname.trim();
        if (nn.isEmpty() || nn.length() > NICKNAME_MAX) {
            throw AppException.validation("昵称必填且不超过 20 字").code("admin.err.virtualAccount.nicknameInvalid");
        }
        if (accountSpecies != null && !accountSpecies.isBlank()
                && !ContentSpecies.isValid(accountSpecies)) {
            throw AppException.validation("账号物种定位取值须是 " + ContentSpecies.ALL)
                    .code("admin.err.virtualAccount.speciesInvalid", ContentSpecies.ALL);
        }
        User u = users.save(User.newVirtual("virtual:" + UUID.randomUUID(), nn,
                blankToNull(avatarUrl), adminId));
        if (accountSpecies != null && !accountSpecies.isBlank()) {
            u.setAccountSpecies(accountSpecies);
            users.save(u);
        }
        audit.record(adminId, "VIRTUAL_ACCOUNT_CREATE", "user", String.valueOf(u.getId()),
                "nickname=" + nn);
        return u.getId();
    }

    /** 启停虚拟账号。仅 VIRTUAL 可操作（防误改真实用户）。 */
    @Transactional
    public void setEnabled(long userId, boolean enabled, long adminId) {
        User u = users.findById(userId)
                .orElseThrow(() -> AppException.notFound("账号不存在").code("admin.err.virtualAccount.accountNotFound"));
        if (u.getAccountType() != AccountType.VIRTUAL) {
            throw AppException.validation("仅虚拟账号可启停").code("admin.err.virtualAccount.virtualOnly");
        }
        if (u.isEnabled() == enabled) {
            return;
        }
        u.setEnabled(enabled);
        users.save(u);
        audit.record(adminId, enabled ? "VIRTUAL_ACCOUNT_ENABLE" : "VIRTUAL_ACCOUNT_DISABLE",
                "user", String.valueOf(userId), "enabled=" + enabled);
    }

    /**
     * 改账号物种定位（V1.1.6 Story 14.1 · AC2）。
     *
     * <p>✅ <b>可随时修改，修改即时影响该号全部历史内容的物种归属</b> ——
     * 因为物种是**读时 join 推导**而不是发布时快照。零回填。
     */
    @Transactional
    public void setAccountSpecies(long userId, String species, long adminId) {
        if (!ContentSpecies.isValid(species)) {
            throw AppException.validation("账号物种定位取值须是 " + ContentSpecies.ALL)
                    .code("admin.err.virtualAccount.speciesInvalid", ContentSpecies.ALL);
        }
        User u = users.findById(userId)
                .orElseThrow(() -> AppException.notFound("账号不存在")
                        .code("admin.err.virtualAccount.accountNotFound"));
        if (u.getAccountType() != AccountType.VIRTUAL) {
            // 🔴 真实账号刻意没有这个字段：它们有真实宠物档案，让算法读档案比贴标签准确。
            throw AppException.validation("仅虚拟账号有「账号物种定位」")
                    .code("admin.err.virtualAccount.speciesVirtualOnly");
        }
        u.setAccountSpecies(species);
        users.save(u);
        audit.record(adminId, "VIRTUAL_ACCOUNT_SET_SPECIES", "user", String.valueOf(userId),
                "species=" + species);
    }

    private static VirtualAccountRow toRow(User u) {
        return new VirtualAccountRow(u.getId(), u.getNickname(), u.getAvatarUrl(), u.isEnabled(),
                u.getPublishedCount(),
                com.tailtopia.content.species.ContentSpeciesResolver.effectiveAccountSpecies(u),
                u.getCreatedBy(), u.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
