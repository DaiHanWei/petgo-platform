package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 等级变化的<b>单一连锁出口</b>（V1.4.0 ① Story 1.2 · 架构 AD-10 / §5.4）。🔴 等级变化的一切连锁（更新 level、
 * {@link GrowthLevelChangedEvent}、Story 2.1 的待弹庆祝与升级通知）只在这一处。
 *
 * <p>在<b>调用方的同一事务</b>内（{@code MANDATORY}，无事务即抛）、账户行已被锁时调用：按等级表算新等级，与落库值不同则更新并发事件（升降都发）。
 * 一次入账只调一次，比较的是本次变化前后的落库等级 → 连跳多级自然只发一条。② 用 AFTER_COMMIT 接事件，回滚不误算名额。
 */
@Component
public class GrowthLevelChangeHandler {

    static final String LEVEL_FOR_TOTAL_SQL =
            "SELECT COALESCE(MAX(level), 1) FROM growth_levels WHERE threshold <= :total";

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public GrowthLevelChangeHandler(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events) {
        this(jdbc, events, Clock.systemUTC());
    }

    GrowthLevelChangeHandler(NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 累计变化后重算等级。
     *
     * @param lockedAccount 已 {@code FOR UPDATE} 锁住的账户行（本方法改它的 level）
     * @param source        本次流水的规则代码（扣回传原流水规则代码）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLifetimeChanged(GrowthAccount lockedAccount, RuleCode source) {
        int oldLevel = lockedAccount.getLevel();
        int newLevel = levelFor(lockedAccount.getLifetimeTotal());
        if (newLevel == oldLevel) {
            return;
        }
        lockedAccount.changeLevel(newLevel, clock.instant());
        // Story 2.1：只有升级且来源不是存量补算才写待弹庆祝；降级不写、不清（读时校正，见 GrowthPopupService）。
        // 升级通知由 GrowthNotifyListener 在 AFTER_COMMIT 消费下面这个事件发出（入账回滚则不发）。
        if (newLevel > oldLevel && source != RuleCode.LAUNCH_BACKFILL) {
            lockedAccount.markCelebration(oldLevel, newLevel, clock.instant());
        }
        events.publishEvent(new GrowthLevelChangedEvent(lockedAccount.getUserId(), oldLevel, newLevel));
    }

    /** 累计值对应的等级：{@code max(level) where threshold <= total}（一条 SQL，不缓存）。包级可见供批量重算复用。 */
    int levelFor(long lifetimeTotal) {
        Integer level = jdbc.queryForObject(LEVEL_FOR_TOTAL_SQL, Map.of("total", lifetimeTotal), Integer.class);
        return level == null ? 1 : level;
    }
}
