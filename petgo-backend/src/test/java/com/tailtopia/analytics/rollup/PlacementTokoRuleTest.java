package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.rollup.PlacementTokoRule.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** L0：「运营位是否推广 Toko」判定（analytics-pipeline Story 4.4 T3 / T8，FR-17 第 3 条）。 */
class PlacementTokoRuleTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
        "PRODUCT,    abc123,        true",
        "CATEGORY,   DRY_FOOD,      true",
        "APP_PAGE,   TOKO,          true",
        "APP_PAGE,   HOME,          false",
        "APP_PAGE,   PUBLISH,       false",
        "APP_PAGE,   KNOW_YOUR_PET, false",
        "APP_PAGE,   HEALTH,        false",
        "APP_PAGE,   toko,          false",
        "APP_PAGE,   NULL,          false",
        "NONE,       NULL,          false",
        "EXTERNAL,   https://x.id,  false",
        "SOMETHING,  TOKO,          false"
    })
    void opsPopupByTarget(String type, String value, boolean expected) {
        assertThat(PlacementTokoRule.promotesToko("ops_popup", new Target(type, value))).isEqualTo(expected);
        // 运营推送与弹窗同一口径
        assertThat(PlacementTokoRule.opsTarget(type, value)).isEqualTo(expected);
    }

    @Test
    void opsTargetNullTypeIsFalse() {
        assertThat(PlacementTokoRule.opsTarget(null, "TOKO")).isFalse();
    }

    @Test
    void bannerAlwaysTokoWhenConfigExists() {
        for (String t : new String[] {"NONE", "PRODUCT", "CATEGORY", "EXTERNAL", "COUPON"}) {
            assertThat(PlacementTokoRule.promotesToko("toko_banner", new Target(t, null))).isTrue();
        }
    }

    @Test
    void newbieCouponAlwaysToko() {
        assertThat(PlacementTokoRule.promotesToko("newbie_coupon", null)).isTrue();
        assertThat(PlacementTokoRule.promotesToko("newbie_coupon", new Target("NONE", null))).isTrue();
    }

    /** 配置已删（查不到 token）→ 空；未知运营位类型 → 空。 */
    @Test
    void missingConfigOrUnknownTypeIsNull() {
        assertThat(PlacementTokoRule.promotesToko("ops_popup", null)).isNull();
        assertThat(PlacementTokoRule.promotesToko("toko_banner", null)).isNull();
        assertThat(PlacementTokoRule.promotesToko("home_banner", new Target("PRODUCT", "x"))).isNull();
        assertThat(PlacementTokoRule.promotesToko(null, null)).isNull();
    }
}
