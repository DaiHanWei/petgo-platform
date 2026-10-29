package com.tailtopia.auth.service;

import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会员等级的唯一写入口（专题线 video · Story 1.1 · FR-2）。
 *
 * <p>{@code users.vip_level} 属 auth 模块；后台经本服务改，不直写 {@code users}。
 *
 * <p>🔴 实体字段只读映射，这里只走 native 定向 UPDATE —— 不可改成「load → set → save」，
 * 否则与 {@code MeService.updateMe} 等整行 save 的路径互相覆盖（丢更新）。
 */
@Service
public class VipLevelService {

    /** 取值下界（含）。 */
    public static final int MIN_LEVEL = 1;
    /** 取值上界（含），与 DB {@code ck_users_vip_level} 一致。 */
    public static final int MAX_LEVEL = 99;
    /** 发视频门槛（FR-4）。 */
    public static final int VIDEO_PUBLISH_LEVEL = 10;

    private final UserRepository users;

    public VipLevelService(UserRepository users) {
        this.users = users;
    }

    /** 当前等级（直接查库，不用实体快照）；用户不存在 → 404。 */
    @Transactional(readOnly = true)
    public int currentLevel(long userId) {
        Integer level = users.findVipLevel(userId);
        if (level == null) {
            throw AppException.notFound("用户不存在").code("admin.err.user.notFound");
        }
        return level;
    }

    /** 是否具备发视频权限（等级 ≥ {@value #VIDEO_PUBLISH_LEVEL}）。用户不存在视为否。 */
    @Transactional(readOnly = true)
    public boolean canPublishVideo(long userId) {
        Integer level = users.findVipLevel(userId);
        return level != null && level >= VIDEO_PUBLISH_LEVEL;
    }

    /**
     * 改等级。越界 → 422；用户不存在 → 404；已注销 → 422（native UPDATE 带 {@code deleted_at IS NULL}，影响 0 行）。
     *
     * @return 修改前的等级（供审计摘要 {@code old→new}）
     */
    @Transactional
    public int setLevel(long userId, int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw AppException.validation("会员等级须在 1–99 之间").code("admin.err.user.vipLevelRange");
        }
        // 锁行读旧值：两位运营同时改同一个人时，第二个事务等第一个提交后读到的是新值，审计 old→new 才准（code-review 2026-10-16）。
        Integer locked = users.findVipLevelForUpdate(userId);
        if (locked == null) {
            throw AppException.notFound("用户不存在").code("admin.err.user.notFound");
        }
        int old = locked;
        if (users.updateVipLevel(userId, level) == 0) {
            throw AppException.validation("该账号已注销，不可修改会员等级").code("admin.err.user.deletedNoVipLevel");
        }
        return old;
    }
}
