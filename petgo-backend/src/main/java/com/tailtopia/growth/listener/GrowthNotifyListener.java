package com.tailtopia.growth.listener;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 升级通知 + 推送（V1.4.0 ① Story 2.1 · AD-10，UI 稿 F1 / F2）。消费 {@link GrowthLevelChangedEvent}（AFTER_COMMIT：
 * 入账回滚则不发），只在升级时发一条 {@code GROWTH_LEVEL_UP}；降级 / 不变不发。一次入账连跳多级只有一个事件 → 一条通知。
 * 存量补算迁移不发事件，天然不发通知。
 *
 * <h2>{@code targetRef} 契约（对架构 AD-10 的补充）</h2>
 * {@code targetRef = "{toLevel}:{TIER}"}（如 {@code 7:BUDDY}）：App 通知中心按代码本地化标题「Naik ke Lv{n}!」与段位名，
 * 只能从这里取等级与段位（App 不渲染后端显示串）。段位取发通知那一刻的等级表，之后改表不回改历史通知。
 * 推送文案键 {@code notify.GROWTH_LEVEL_UP.<TIER>.title/body}（{0} = 等级字符串）。落库 title/body 只作留痕。
 *
 * <p>已知取舍：降级后再升回同一级会再发一条（不为此加「最高已通知等级」列）；失败只记 ERROR、不重试（AD-5）。
 */
@Component
public class GrowthNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(GrowthNotifyListener.class);

    private final NotificationService notificationService;
    private final AccountQueryService accountQuery;
    private final GrowthLevelDefRepository levels;

    public GrowthNotifyListener(NotificationService notificationService, AccountQueryService accountQuery,
            GrowthLevelDefRepository levels) {
        this.notificationService = notificationService;
        this.accountQuery = accountQuery;
        this.levels = levels;
    }

    @Async
    @TransactionalEventListener
    public void onLevelChanged(GrowthLevelChangedEvent e) {
        if (e.newLevel() <= e.oldLevel()) {
            return;
        }
        try {
            // 双保险：注销 / 停用 / 虚拟号不会走到这里（入账参与判定已挡住）。
            if (!accountQuery.isActive(e.userId()) || accountQuery.isVirtual(e.userId())) {
                return;
            }
            GrowthTier tier = levels.findById(e.newLevel()).map(GrowthLevelDef::getTier).orElse(GrowthTier.NEWBIE);
            String level = String.valueOf(e.newLevel());
            notificationService.sendWithCopy(e.userId(), NotificationType.GROWTH_LEVEL_UP,
                    "GROWTH_LEVEL_UP." + tier.name(),
                    new Object[] {level},
                    "Naik ke Lv" + level + "!",
                    "Kamu sekarang " + tierNameId(tier) + ". Lihat keuntungan barumu.",
                    level + ":" + tier.name());
        } catch (RuntimeException ex) {
            log.error("growth level-up notify failed: userId={} level={} error={}", e.userId(), e.newLevel(),
                    ex.getClass().getSimpleName());
        }
    }

    /** 落库留痕用的印尼语段位名（App 不读，推送走 i18n 键）。 */
    static String tierNameId(GrowthTier tier) {
        return switch (tier) {
            case NEWBIE -> "Pemula";
            case BUDDY -> "Sahabat";
            case GUARDIAN -> "Penjaga";
            case STAR -> "Bintang";
        };
    }
}
