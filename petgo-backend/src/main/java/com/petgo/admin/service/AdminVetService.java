package com.petgo.admin.service;

import com.petgo.consult.dto.VetRatingsView;
import com.petgo.consult.service.ConsultInterruptService;
import com.petgo.consult.service.ConsultRatingQueryService;
import com.petgo.vet.domain.VetAccount;
import com.petgo.vet.domain.VetStatus;
import com.petgo.vet.service.VetAccountService;
import com.petgo.vet.service.VetPresenceService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin slice：兽医账号 CRUD（Story 5.1，G-1 跨切面 slice）。
 *
 * <p>复用 {@link VetAccountService}（不自建 vet repository，遵守「禁跨模块直访对方 repository」）。
 * 本服务是 5.6 评分查看、5.7 封禁的同一 Admin 落点。明文密码绝不外泄到日志/视图。
 */
@Service
public class AdminVetService {

    private final VetAccountService vetAccounts;
    private final ConsultRatingQueryService ratingQuery;
    private final VetPresenceService presence;
    private final ConsultInterruptService interruptService;

    public AdminVetService(VetAccountService vetAccounts, ConsultRatingQueryService ratingQuery,
            VetPresenceService presence, ConsultInterruptService interruptService) {
        this.vetAccounts = vetAccounts;
        this.ratingQuery = ratingQuery;
        this.presence = presence;
        this.interruptService = interruptService;
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

    /**
     * 切换封禁/解封（Story 5.1 建 + 5.7 补副作用）。
     *
     * <p>封禁（banned=true）：status=BANNED + 清在线态（踢下线）+ 批量中断进行中会话（同事务原子）。
     * 解封（banned=false）：仅 status=ACTIVE，<b>不恢复已中断会话</b>（中断不可恢复，用户须重新发起）。
     */
    @Transactional
    public void setBanned(long vetId, boolean banned) {
        vetAccounts.setStatus(vetId, banned ? VetStatus.BANNED : VetStatus.ACTIVE);
        if (banned) {
            presence.goOffline(vetId);              // 清在线态 + 移出待接单匹配
            interruptService.interruptByVetBan(vetId); // 进行中/待关闭会话迁 INTERRUPTED
        }
    }

    /** 兽医历史评分 + 平均分（Story 5.6，AC4，仅运营可见）。经 consult service 聚合，不跨 repository。 */
    public VetRatingsView ratings(long vetId) {
        return ratingQuery.forVet(vetId);
    }

    public VetAdminView view(long vetId) {
        return VetAdminView.from(vetAccounts.getById(vetId));
    }
}
