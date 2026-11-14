package com.tailtopia.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.onboarding.domain.OnboardingMarkKey;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 1.1 · AC8 —— 首日推荐页「只出现一次」的引导键。 */
class OnboardingMarkKeyTest {

    @Test
    void 首日推荐键已登记且线格式小写() {
        assertThat(OnboardingMarkKey.fromWire("first_day_recommend"))
                .contains(OnboardingMarkKey.FIRST_DAY_RECOMMEND);
        assertThat(OnboardingMarkKey.FIRST_DAY_RECOMMEND.wire()).isEqualTo("first_day_recommend");
    }

    @Test
    void 新键追加在末尾不打乱旧键() {
        OnboardingMarkKey[] all = OnboardingMarkKey.values();
        assertThat(all[0]).isEqualTo(OnboardingMarkKey.KTP_MOVED);
        assertThat(all[1]).isEqualTo(OnboardingMarkKey.TAILSONALITY_ENTRY);
        assertThat(all[all.length - 1]).isEqualTo(OnboardingMarkKey.FIRST_DAY_RECOMMEND);
    }

    @Test
    void 未登记键仍拒() {
        assertThat(OnboardingMarkKey.fromWire("FIRST_DAY_RECOMMEND")).isEmpty();
        assertThat(OnboardingMarkKey.fromWire("first_day")).isEmpty();
    }
}
