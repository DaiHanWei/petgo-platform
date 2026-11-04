package com.tailtopia.social.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.social.repository.PetFollowRepository;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 关注人 + 追更宠物<b>共用</b>的每日新增额度（V1.4.2 social-topic · Story 1.1 · D-8 / P-6）。
 *
 * <p>当日（WIB 自然日，日界 {@link ScheduleWindow#WIB}）「{@code user_follows} 中 {@code source='USER'} 且仍存在的新建行」
 * + 「{@code pet_follows} 中仍存在的新建行」≥ {@value #DAILY_LIMIT} → 429。
 * {@link FollowService#follow} 与 {@link PetFollowService#follow} 都只经这里判定，不各写一遍。
 */
@Component
public class FollowDailyQuota {

    /** 每日新增关注 + 追更合计上限（AD-4 / T-3 / D-8）。 */
    static final int DAILY_LIMIT = 500;

    private final UserFollowRepository follows;
    private final PetFollowRepository petFollows;
    private final Clock clock;

    @Autowired
    public FollowDailyQuota(UserFollowRepository follows, PetFollowRepository petFollows) {
        this(follows, petFollows, Clock.systemUTC());
    }

    FollowDailyQuota(UserFollowRepository follows, PetFollowRepository petFollows, Clock clock) {
        this.follows = follows;
        this.petFollows = petFollows;
        this.clock = clock;
    }

    /** 当日两表合计已达上限 → 429 {@code rate-limited}。 */
    public void check(long userId) {
        if (usedToday(userId) >= DAILY_LIMIT) {
            throw AppException.rateLimited("今日关注次数已达上限");
        }
    }

    /** 当日（WIB）两表合计新建且仍存在的行数。 */
    long usedToday(long userId) {
        Instant dayStart = dayStart();
        return follows.countCreatedSince(userId, dayStart) + petFollows.countCreatedSince(userId, dayStart);
    }

    /** 当天 WIB 00:00 对应的 UTC 时刻。 */
    Instant dayStart() {
        return clock.instant().atZone(ScheduleWindow.WIB).toLocalDate()
                .atStartOfDay(ScheduleWindow.WIB).toInstant();
    }
}
