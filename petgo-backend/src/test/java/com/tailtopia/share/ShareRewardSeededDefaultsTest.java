package com.tailtopia.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：分享奖励的**种子默认值 = 不发币**（2026-08-26 产品决定）。
 *
 * <h2>为什么需要这条</h2>
 * 🔴 这是个**产品决定**，不是实现细节：功能随版本上线，但默认一分不发，
 * 等产品在后台把数配上。没有这条守护，谁把迁移里的 {@code DEFAULT} 改回一个非零数，
 * <b>整套测试照样全绿</b>（其余测试都显式配置自己的取值），
 * 而线上会在没人做过决定的情况下开始发币。
 *
 * <h2>⚠️ 断言的是「列默认值」，不是「当前行的值」</h2>
 * 当前行的值是**运营可改**的，而且共享测试库里别的测试会改它 ——
 * 拿它断言会得到一条时不时红、且红的时候与本意无关的测试。
 * 列默认值是 schema 的属性，只有改迁移才会变，正是要守的东西。
 */
class ShareRewardSeededDefaultsTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String columnDefault(String column) {
        return jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_name = 'pawcoin_config' AND column_name = ?
                """, String.class, column);
    }

    /**
     * 🔴 三个数全部默认 0 = 不发币。
     *
     * <p>⚠️ 闸门是**串联**的：任意一个是 0 都不会发。三个都断言，是因为
     * 「改回其中一个」和「三个都改」同样会让线上开始发币。
     */
    @Test
    void allThreeAmountsDefaultToZeroMeaningNothingIsGranted() {
        assertThat(columnDefault("share_reward_monthly_cap"))
                .as("🔴 月度上限的默认值被改动了 —— 产品定的是 0（不发），改它等于让线上开始发币")
                .isEqualTo("0");
        assertThat(columnDefault("id_card_share_reward"))
                .as("🔴 身份证分享每次发放枚数的默认值被改动了")
                .isEqualTo("0");
        assertThat(columnDefault("id_card_share_daily_cap"))
                .as("🔴 身份证分享日上限的默认值被改动了")
                .isEqualTo("0");
    }

    /** V1.3.2 Story 4.5：两个新渠道四项同样默认 0（RC-3 由运营配）。 */
    @Test
    void tailsonalityAndPassportChannelsDefaultToZero() {
        for (String col : java.util.List.of("tailsonality_share_reward", "tailsonality_share_daily_cap",
                "passport_share_reward", "passport_share_daily_cap")) {
            assertThat(columnDefault(col)).as("🔴 %s 的默认值被改动了", col).isEqualTo("0");
        }
    }

    /**
     * 总开关列默认值<b>关</b>（V1.4.2 shop-admin Story 3.2 · AD-8 / 决策 O-1，迁移 V20261106_1010）。
     *
     * <p>原「默认开 = 没有紧急情况」的语义已被 O-1 推翻：分享奖励发的是真钱（PawCoin），新环境 / 新插入行
     * 默认不发，由运营在后台确认后再打开。⚠️ 该迁移只改列默认值，不改线上已有那一行的值。
     */
    @Test
    void masterSwitchDefaultsToOffPerDecisionO1() {
        assertThat(columnDefault("share_reward_enabled")).isEqualTo("false");
    }
}
