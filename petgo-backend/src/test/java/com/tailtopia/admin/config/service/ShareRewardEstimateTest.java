package com.tailtopia.admin.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.config.dto.ShareRewardForm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.4.2 shop-admin Story 3.2 · AD-8）：每个账号每月最多可获得 = min(月度上限, Σ(每次 × 每日上限) × 当月天数)，
 * 当月天数按 WIB（大月 / 小月 / 平闰年 2 月）；迁移只改默认值、不改现有行（O-1）。
 */
class ShareRewardEstimateTest {

    private static ShareRewardForm form(long cap, long id, int idDaily, long age, int ageDaily, long tail, int tailDaily,
            long pass, int passDaily) {
        return new ShareRewardForm(true, cap, id, idDaily, age, ageDaily, tail, tailDaily, pass, passDaily);
    }

    @Test
    void sumsAllFourChannelsTimesDaysOfTheMonth() {
        // 每日 = 10×1 + 5×2 + 20×1 + 0×3 = 40
        ShareRewardForm f = form(100_000, 10, 1, 5, 2, 20, 1, 0, 3);
        assertThat(ShareRewardEstimate.dailySum(f)).isEqualTo(40);
        assertThat(ShareRewardEstimate.monthlyMaxPerAccount(f, YearMonth.of(2026, 1))).isEqualTo(40 * 31);  // 大月
        assertThat(ShareRewardEstimate.monthlyMaxPerAccount(f, YearMonth.of(2026, 11))).isEqualTo(40 * 30); // 小月
        assertThat(ShareRewardEstimate.monthlyMaxPerAccount(f, YearMonth.of(2026, 2))).isEqualTo(40 * 28);  // 平年 2 月
        assertThat(ShareRewardEstimate.monthlyMaxPerAccount(f, YearMonth.of(2028, 2))).isEqualTo(40 * 29);  // 闰年 2 月
    }

    @Test
    void monthlyCapWinsWhenLowerAndZeroMeansNothing() {
        ShareRewardForm f = form(2_000, 50, 3, 0, 0, 0, 0, 0, 0);   // 150 × 30 = 4500 > 2000
        assertThat(ShareRewardEstimate.monthlyMaxPerAccount(f, YearMonth.of(2026, 11))).isEqualTo(2_000);
        assertThat(ShareRewardEstimate.monthlyMaxPerAccount(form(0, 50, 3, 0, 0, 0, 0, 0, 0), YearMonth.of(2026, 11)))
                .isZero();
    }

    @Test
    void monthIsTakenInWib() {
        // 2026-11-30T18:00Z = WIB 12-01 01:00 → 12 月（31 天）
        assertThat(ShareRewardEstimate.currentMonth(Instant.parse("2026-11-30T18:00:00Z"))).isEqualTo(YearMonth.of(2026, 12));
        assertThat(ShareRewardEstimate.currentMonth(Instant.parse("2026-11-30T16:00:00Z"))).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void switchedOffMeansNothingIsPaid() {
        ShareRewardForm off = new ShareRewardForm(false, 100_000, 10, 1, 0, 0, 0, 0, 0, 0);
        ShareRewardEstimate.Result r = ShareRewardEstimate.estimate(off, YearMonth.of(2026, 11));
        assertThat(r.max()).isZero();
        assertThat(r.channelSum()).isEqualTo(300);
        assertThat(r.days()).isEqualTo(30);
        ShareRewardEstimate.Result on = ShareRewardEstimate.estimate(form(100_000, 10, 1, 0, 0, 0, 0, 0, 0), YearMonth.of(2026, 11));
        assertThat(on.max()).isEqualTo(300);
    }

    @Test
    void turningTheSwitchOnAlsoShowsTheEstimate() {
        ShareRewardForm off = new ShareRewardForm(false, 2_000, 10, 1, 0, 0, 0, 0, 0, 0);
        assertThat(ShareRewardEstimate.amountsChanged(off, form(2_000, 10, 1, 0, 0, 0, 0, 0, 0))).isTrue();
    }

    @Test
    void onlyAmountChangesTriggerTheEstimate() {
        ShareRewardForm a = form(2_000, 10, 1, 0, 0, 0, 0, 0, 0);
        ShareRewardForm toggled = new ShareRewardForm(false, 2_000, 10, 1, 0, 0, 0, 0, 0, 0);
        assertThat(ShareRewardEstimate.amountsChanged(a, toggled)).isFalse();
        assertThat(ShareRewardEstimate.amountsChanged(a, form(2_000, 12, 1, 0, 0, 0, 0, 0, 0))).isTrue();
        assertThat(ShareRewardEstimate.amountsChanged(a, form(3_000, 10, 1, 0, 0, 0, 0, 0, 0))).isTrue();
    }

    /** AC2 / O-1：迁移只 ALTER … SET DEFAULT false，不改线上已有那一行（不得出现 UPDATE / INSERT / DELETE）。 */
    @Test
    void migrationOnlyChangesTheDefault() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V20261106_1010__pawcoin_config_share_reward_default_off.sql"),
                StandardCharsets.UTF_8);
        String code = sql.replaceAll("(?m)--.*$", "");
        assertThat(code).containsIgnoringCase("ALTER COLUMN share_reward_enabled SET DEFAULT false");
        assertThat(code.toUpperCase()).doesNotContain("UPDATE ").doesNotContain("INSERT ").doesNotContain("DELETE ");
    }
}
