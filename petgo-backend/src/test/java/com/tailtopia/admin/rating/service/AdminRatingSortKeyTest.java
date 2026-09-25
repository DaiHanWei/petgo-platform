package com.tailtopia.admin.rating.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.0 Story 9.1b）：排序键归一。
 *
 * <p>🔴 这条测试的存在理由是一次真实的事故：兽医列表的筛选栏按 AC3 的行文用了
 * camelCase（{@code avgDesc}），而服务层的常量一直是 snake_case（{@code avg_desc}）——
 * 于是「均分低→高」和「问诊量多→少」两个选项**静默变成默认的均分倒序**：
 * 下拉框仍高亮着运营选的那一项，界面上一点异常都看不出，只能靠人肉核对数字。
 *
 * <p>所以这里逐个钉住**六种拼法**，而不是只验一个方向 —— 只验 avgDesc 的话，
 * 它恰好撞进 default，测试照样绿。
 */
class AdminRatingSortKeyTest {

    @Test
    void bothSpellingsOfEverySortKeyResolveToTheSameKey() {
        assertThat(AdminRatingService.normalizeSort("avg_desc")).isEqualTo(AdminRatingService.AVG_DESC);
        assertThat(AdminRatingService.normalizeSort("avgDesc")).isEqualTo(AdminRatingService.AVG_DESC);
        assertThat(AdminRatingService.normalizeSort("avg_asc")).isEqualTo(AdminRatingService.AVG_ASC);
        assertThat(AdminRatingService.normalizeSort("avgAsc")).isEqualTo(AdminRatingService.AVG_ASC);
        assertThat(AdminRatingService.normalizeSort("volume_desc")).isEqualTo(AdminRatingService.VOLUME_DESC);
        assertThat(AdminRatingService.normalizeSort("volumeDesc")).isEqualTo(AdminRatingService.VOLUME_DESC);
    }

    /** 🔴 三个键必须**各不相同** —— 全归一到同一个值也能让上面那条绿。 */
    @Test
    void theThreeKeysStayDistinct() {
        assertThat(AdminRatingService.AVG_DESC)
                .isNotEqualTo(AdminRatingService.AVG_ASC)
                .isNotEqualTo(AdminRatingService.VOLUME_DESC);
        assertThat(AdminRatingService.AVG_ASC).isNotEqualTo(AdminRatingService.VOLUME_DESC);
    }

    /** 空 / 空白 = 不排序（调用方据此保持原顺序）；未知值保守退化到默认，与改前一致。 */
    @Test
    void blankMeansNoSortAndUnknownFallsBackToDefault() {
        assertThat(AdminRatingService.normalizeSort(null)).isNull();
        assertThat(AdminRatingService.normalizeSort("  ")).isNull();
        assertThat(AdminRatingService.normalizeSort("nonsense")).isEqualTo("nonsense");
    }
}
