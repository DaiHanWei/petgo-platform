package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.domain.ContentType;
import org.junit.jupiter.api.Test;

/** L0：内容类型 → 属性映射（Story 16.2 · AC1）。 */
class FeedAttributeTest {

    @Test
    void mapsThreeContentTypes() {
        assertThat(FeedAttribute.from(ContentType.KNOWLEDGE, true)).isEqualTo(FeedAttribute.EDU);
        assertThat(FeedAttribute.from(ContentType.DAILY, true)).isEqualTo(FeedAttribute.FUN);
        assertThat(FeedAttribute.from(ContentType.GROWTH_MOMENT, true)).isEqualTo(FeedAttribute.LIFE);
    }

    /** ⚠️ 非公开的成长日历不属于任何属性 —— 返回 null 让它排不进去，而不是抛错。 */
    @Test
    void nonPublicGrowthMomentHasNoAttribute() {
        assertThat(FeedAttribute.from(ContentType.GROWTH_MOMENT, false)).isNull();
    }

    /** 日常/科普与可见性无关（候选池已保证 PUBLIC，这里只钉映射本身不看那个参数）。 */
    @Test
    void dailyAndKnowledgeIgnoreVisibility() {
        assertThat(FeedAttribute.from(ContentType.DAILY, false)).isEqualTo(FeedAttribute.FUN);
        assertThat(FeedAttribute.from(ContentType.KNOWLEDGE, false)).isEqualTo(FeedAttribute.EDU);
    }

    @Test
    void nullTypeHasNoAttribute() {
        assertThat(FeedAttribute.from(null, true)).isNull();
    }
}
