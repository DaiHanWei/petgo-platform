package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.virtual.dto.VirtualAccountRow;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
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
        return users.findByAccountTypeOrderByIdDesc(AccountType.VIRTUAL).stream()
                .map(AdminVirtualAccountService::toRow).toList();
    }

    /** 建虚拟账号（无登录）。昵称必填 ≤20；头像选填。 */
    @Transactional
    public long create(String nickname, String avatarUrl, long adminId) {
        String nn = nickname == null ? "" : nickname.trim();
        if (nn.isEmpty() || nn.length() > NICKNAME_MAX) {
            throw AppException.validation("昵称必填且不超过 20 字");
        }
        User u = users.save(User.newVirtual("virtual:" + UUID.randomUUID(), nn,
                blankToNull(avatarUrl), adminId));
        audit.record(adminId, "VIRTUAL_ACCOUNT_CREATE", "user", String.valueOf(u.getId()),
                "nickname=" + nn);
        return u.getId();
    }

    /** 启停虚拟账号。仅 VIRTUAL 可操作（防误改真实用户）。 */
    @Transactional
    public void setEnabled(long userId, boolean enabled, long adminId) {
        User u = users.findById(userId)
                .orElseThrow(() -> AppException.notFound("账号不存在"));
        if (u.getAccountType() != AccountType.VIRTUAL) {
            throw AppException.validation("仅虚拟账号可启停");
        }
        if (u.isEnabled() == enabled) {
            return;
        }
        u.setEnabled(enabled);
        users.save(u);
        audit.record(adminId, enabled ? "VIRTUAL_ACCOUNT_ENABLE" : "VIRTUAL_ACCOUNT_DISABLE",
                "user", String.valueOf(userId), "enabled=" + enabled);
    }

    private static VirtualAccountRow toRow(User u) {
        return new VirtualAccountRow(u.getId(), u.getNickname(), u.getAvatarUrl(), u.isEnabled(),
                u.getPublishedCount(), u.getCreatedBy(), u.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
