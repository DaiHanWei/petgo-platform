package com.petgo.admin.service;

import com.petgo.vet.domain.VetAccount;
import com.petgo.vet.domain.VetStatus;
import com.petgo.vet.service.VetAccountService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Admin slice：兽医账号 CRUD（Story 5.1，G-1 跨切面 slice）。
 *
 * <p>复用 {@link VetAccountService}（不自建 vet repository，遵守「禁跨模块直访对方 repository」）。
 * 本服务是 5.6 评分查看、5.7 封禁的同一 Admin 落点。明文密码绝不外泄到日志/视图。
 */
@Service
public class AdminVetService {

    private final VetAccountService vetAccounts;

    public AdminVetService(VetAccountService vetAccounts) {
        this.vetAccounts = vetAccounts;
    }

    public List<VetAdminView> list() {
        return vetAccounts.listAll().stream().map(VetAdminView::from).toList();
    }

    /** 创建兽医账号，返回新建账号 id（明文密码仅本次由运营手填/一次性回显，不再可读）。 */
    public long create(String displayName, String username, String rawPassword) {
        VetAccount created = vetAccounts.create(displayName, username, rawPassword);
        return created.getId();
    }

    public void resetPassword(long vetId, String newRawPassword) {
        vetAccounts.resetPassword(vetId, newRawPassword);
    }

    /** 切换封禁/解封（5.7 复用；本故事落 BANNED 不可登录）。 */
    public void setBanned(long vetId, boolean banned) {
        vetAccounts.setStatus(vetId, banned ? VetStatus.BANNED : VetStatus.ACTIVE);
    }
}
