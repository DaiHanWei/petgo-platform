package com.tailtopia.notify.service;

import com.tailtopia.notify.repository.FollowUpdatePushDao;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 关注更新 + 追更新 Diary 两类<b>合计</b>每人每 WIB 日推送名额（V1.5.0 onboarding-recall Story 5.1 · OR-14 · FR-180 / FR-181）。
 *
 * <p>名额 = {@code petgo.follow-update-push.daily-cap}（默认 3，env {@code FOLLOW_UPDATE_PUSH_DAILY_CAP}）；按 WIB 日计，
 * 00:00 WIB 自然换一行重置。<b>只在确定要推的那一刻抢</b>：关了分类 / 旧版 / 虚拟号 / 官方号 / 同作者今日已推的不占名额；
 * 抢到后推送失败不退还（at-most-once）。{@code USER_POST_PUBLISHED} 与 {@code PET_DIARY_POSTED} 共用本类。
 */
@Component
public class FollowUpdatePushQuota {

    private final FollowUpdatePushDao dao;
    private final int dailyCap;
    private final Clock clock;

    @Autowired
    public FollowUpdatePushQuota(FollowUpdatePushDao dao,
            @Value("${petgo.follow-update-push.daily-cap:3}") int dailyCap) {
        this(dao, dailyCap, Clock.systemUTC());
    }

    FollowUpdatePushQuota(FollowUpdatePushDao dao, int dailyCap, Clock clock) {
        this.dao = dao;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    /** 今天（WIB）。 */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ScheduleWindow.WIB));
    }

    public int dailyCap() {
        return dailyCap;
    }

    /** 在今天（WIB）的名额里占一个；{@code true} = 抢到，可以推。 */
    public boolean tryClaim(long userId) {
        return tryClaim(userId, today());
    }

    /** 在指定 WIB 日的名额里占一个；{@code true} = 抢到。cap ≤ 0 → 恒 false（等于关闭两类推送，通知照写）。 */
    public boolean tryClaim(long userId, LocalDate wibDate) {
        return dao.tryClaimQuota(userId, wibDate, dailyCap);
    }
}
