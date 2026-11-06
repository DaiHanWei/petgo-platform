package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.shop.service.BannerImageRatio.Fit;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 3.1 · B6）：banner 比例 ±3% 判定（只提醒、不拦截）。 */
class BannerImageRatioTest {

    @Test
    void recommendedSizeIsOk() {
        assertThat(BannerImageRatio.check(1056, 444).fit()).isEqualTo(Fit.OK);
        assertThat(BannerImageRatio.check(1056, 444).ratio()).isEqualTo("2.4:1");
        assertThat(BannerImageRatio.check(352, 148).fit()).isEqualTo(Fit.OK);
    }

    @Test
    void withinThreePercentIsOkBeyondIsFlagged() {
        // 352/148 ≈ 2.378；×1.03 ≈ 2.450，×0.97 ≈ 2.307
        assertThat(BannerImageRatio.check(1220, 500).fit()).isEqualTo(Fit.OK);    // 2.44（+2.6%）
        assertThat(BannerImageRatio.check(1240, 500).fit()).isEqualTo(Fit.WIDE);  // 2.48
        assertThat(BannerImageRatio.check(1160, 500).fit()).isEqualTo(Fit.OK);    // 2.32（-2.5%）
        assertThat(BannerImageRatio.check(1140, 500).fit()).isEqualTo(Fit.TALL);  // 2.28（-4.1%）
    }

    @Test
    void tallImageExampleFromTheDesign() {
        BannerImageRatio.Result r = BannerImageRatio.check(1200, 800);
        assertThat(r.fit()).isEqualTo(Fit.TALL);
        assertThat(r.ratio()).isEqualTo("1.5:1");
        assertThat(BannerImageRatio.check(1200, 300).fit()).isEqualTo(Fit.WIDE);
    }

    @Test
    void unknownSizeIsNotFlagged() {
        assertThat(BannerImageRatio.check(0, 0).fit()).isEqualTo(Fit.OK);
    }
}
