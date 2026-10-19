package com.tailtopia.notify.preference;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推送分类开关（V1.4.0 ④ Story 1.1 · 架构增量 AD-3）。
 *
 * <p>只管<b>系统推送</b>：通知中心落库与角标不受影响。推送闸门（Story 1.2）在「落库与加角标之后、调 pusher 之前」
 * 调 {@link #isEnabled} / {@link #mutedAmong}；以后新增的推送出口也必须经本服务（CROSS-STORY-DECISIONS）。
 *
 * <p>不加任何缓存（护栏：禁通用缓存层）—— 每次推送查一次主键索引。
 */
@Service
public class PushPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(PushPreferenceService.class);

    private final UserPushMuteDao dao;
    private final AccountQueryService accounts;

    public PushPreferenceService(UserPushMuteDao dao, AccountQueryService accounts) {
        this.dao = dao;
        this.accounts = accounts;
    }

    /**
     * 该类型的通知此刻是否给该用户发系统推送。「订单与账号」恒为 true、不查库。
     *
     * <p>查询异常 → 返回 true 并 warn（宁多勿漏，与推送去抖 Redis 异常同口径）。
     */
    public boolean isEnabled(long userId, NotificationType type) {
        PushCategory category = PushCategory.of(type);
        if (!category.lockable()) {
            return true;
        }
        try {
            return !dao.isMuted(userId, category);
        } catch (RuntimeException e) {
            log.warn("推送开关查询失败，照常推送 userId={} category={} error={}", userId, category,
                    e.getClass().getSimpleName());
            return true;
        }
    }

    /** 批量：给定用户里关闭了该分类的那些。空集合或不可关分类 → 空集、不查库。异常不吞（调用方自行兜底）。 */
    public Set<Long> mutedAmong(Collection<Long> userIds, PushCategory category) {
        if (userIds == null || userIds.isEmpty() || !category.lockable()) {
            return Set.of();
        }
        return dao.mutedAmong(userIds, category);
    }

    /** 该用户的完整设置：9 项、按 {@link PushCategory} 声明顺序。 */
    public PushSettingsView get(long userId) {
        Set<PushCategory> muted = EnumSet.noneOf(PushCategory.class);
        for (String raw : dao.mutedCategories(userId)) {
            try {
                muted.add(PushCategory.valueOf(raw));
            } catch (IllegalArgumentException e) {
                // 理论上 CHECK 已挡；出现了也不 500，忽略该行。
                log.warn("user_push_mutes 出现未知分类，已忽略 userId={} category={}", userId, raw);
            }
        }
        List<PushSettingsView.Item> items = new ArrayList<>(PushCategory.values().length);
        for (PushCategory c : PushCategory.values()) {
            items.add(new PushSettingsView.Item(c, !muted.contains(c), c.lockable()));
        }
        return new PushSettingsView(List.copyOf(items));
    }

    /**
     * 部分更新（没出现的分类不动）。整单原子：先完整校验、全部通过才写。
     * <ul>
     *   <li>值为 null → 422 {@code validation}；</li>
     *   <li>{@code ORDER_ACCOUNT: false} → 422 {@code push-category-locked}；{@code ORDER_ACCOUNT: true} → 接受、无操作；</li>
     *   <li>关 = 插入（幂等），开 = 删除（幂等）。</li>
     * </ul>
     *
     * <p>写入前确认调用者是存在且未注销的普通用户，否则 404 —— 已注销账号的残留 access token 不得把注销刚清掉的
     * 关闭行再写回来（D1/D2；同 {@code OpsPopupImpressionService.record}）。
     */
    @Transactional
    public PushSettingsView update(long userId, Map<PushCategory, Boolean> changes) {
        if (accounts.findUserById(userId).filter(u -> u.getDeletedAt() == null).isEmpty()) {
            throw AppException.notFound("用户不存在");
        }
        for (Map.Entry<PushCategory, Boolean> e : changes.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw AppException.validation("推送分类开关的值不能为空");
            }
            if (!e.getKey().lockable() && !e.getValue()) {
                throw AppException.pushCategoryLocked("订单与账号类推送不能关闭");
            }
        }
        for (Map.Entry<PushCategory, Boolean> e : changes.entrySet()) {
            PushCategory c = e.getKey();
            if (!c.lockable()) {
                continue;
            }
            if (e.getValue()) {
                dao.unmute(userId, c);
            } else {
                dao.mute(userId, c);
            }
        }
        return get(userId);
    }

    /** 注销清理（AD-6）：users 行只匿名化不删，FK CASCADE 在注销路径不触发 —— 必须显式删。幂等可重跑。 */
    @Transactional
    public void deleteByUserId(long userId) {
        dao.deleteByUserId(userId);
    }
}
