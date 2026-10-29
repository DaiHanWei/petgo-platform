package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户抽屉里与视频相关的两项处置（专题线 video · Story 1.1）：改会员等级（FR-2）、加入 / 移出视频免审名单（FR-22）。
 *
 * <p>单独成类而不塞进 {@link AdminUserService}：那边构造器已有十几个依赖，这两项与停用 / 删除 / 赠币无共享状态。
 * 套路同 {@code AdminUserService.deactivate}：校验 → 经归属模块的服务改 → 同事务写审计。
 */
@Service
public class AdminUserVideoService {

    private final AccountQueryService accountQuery;
    private final VipLevelService vipLevels;
    private final VideoTrustedPublisherAdminService trusted;
    private final AdminAuditService auditService;

    public AdminUserVideoService(AccountQueryService accountQuery, VipLevelService vipLevels,
            VideoTrustedPublisherAdminService trusted, AdminAuditService auditService) {
        this.accountQuery = accountQuery;
        this.vipLevels = vipLevels;
        this.trusted = trusted;
        this.auditService = auditService;
    }

    /**
     * 改会员等级。越界 → 422；不存在 → 404；已注销 → 422。等级未变化不写审计。
     *
     * @return 修改前的等级
     */
    @Transactional
    public int setVipLevel(long userId, int level, long actorAccountId) {
        User target = accountQuery.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        if (target.getDeletedAt() != null) {
            throw AppException.validation("该账号已注销，不可修改会员等级").code("admin.err.user.deletedNoVipLevel");
        }
        int old = vipLevels.setLevel(userId, level);
        if (old != level) {
            auditService.record(actorAccountId, AuditActions.USER_VIP_LEVEL_CHANGE, "USER",
                    String.valueOf(userId), "vip_level " + old + "→" + level);
        }
        return old;
    }

    /** 加入视频免审名单（幂等）。 */
    @Transactional
    public boolean addTrusted(long userId, long actorAccountId) {
        return trusted.add(userId, actorAccountId).changed();
    }

    /** 移出视频免审名单（幂等）。 */
    @Transactional
    public boolean removeTrusted(long userId, long actorAccountId) {
        return trusted.remove(userId, actorAccountId).changed();
    }

    /** 抽屉展示用：是否在视频免审名单内。 */
    @Transactional(readOnly = true)
    public boolean isTrusted(long userId) {
        return trusted.isTrusted(userId);
    }
}
