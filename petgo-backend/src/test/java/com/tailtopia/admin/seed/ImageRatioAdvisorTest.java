package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.seed.service.ImageRatioAdvisor;
import com.tailtopia.admin.seed.service.ImageRatioAdvisor.Crop;
import com.tailtopia.content.domain.ImageSize;
import org.junit.jupiter.api.Test;

/**
 * L0：上传图会被 Feed 裁掉多少（V1.1.6 Story 12.2 · AC3）。
 *
 * <p><b>这个算式为什么值得单测</b>：它是运营唯一能看到的"我这张图会不会被毁"的依据。
 * 算错了不会崩、不会报警 —— 只会让运营照着一个错数字做决定。
 */
class ImageRatioAdvisorTest {

    @Test
    void ratiosInsideTheFeedRangeAreNotWarnedAbout() {
        // 1:1、4:5（0.8）、4:3（1.333）都在 0.75~1.34 内 —— 后两个正是区间的两个边界附近。
        for (ImageSize s : new ImageSize[] {
                new ImageSize(1000, 1000), new ImageSize(800, 1000), new ImageSize(1332, 1000)}) {
            assertThat(ImageRatioAdvisor.advise(s).warns()).as("%s 应不警告", s).isFalse();
        }
    }

    /**
     * 🔴 <b>16:9 的正确结论是「共裁约 25%、每侧约 12%」。</b>
     *
     * <p>story 里的示例文案写的是「左右**各**裁切约 25%」—— 那个说法会让运营以为要裁掉一半。
     * 正确算法：容器比例被 clamp 到 1.34，按高对齐 ⇒ 可见宽度占 1.34/1.78 ≈ 75.3%，
     * 即共裁 ≈ 24.7%，每侧 ≈ 12.3%。所以文案里**两个数都给**，只给一个必然被读错。
     */
    @Test
    void sixteenByNineCropsAboutAQuarterInTotalNotPerSide() {
        ImageRatioAdvisor.Advice a = ImageRatioAdvisor.advise(new ImageSize(1920, 1080));

        assertThat(a.crop()).isEqualTo(Crop.SIDES);
        assertThat(a.totalPercent()).isEqualTo(25);
        assertThat(a.perSidePercent()).isEqualTo(12);
        // ⚠️ **不能**断言 perSide == round(total / 2)：两个数各自从同一个小数四舍五入
        //    （24.7% → 25 与 12.36% → 12），而 round(25/2) = 13。
        //    先写成那样红了一次 —— 那是断言错，不是实现错。
        //    这里钉的是它们的真实关系：每侧约为总量的一半，误差不超过 1 个百分点。
        assertThat(a.perSidePercent() * 2).isBetween(a.totalPercent() - 1, a.totalPercent() + 1);
    }

    /** 竖图（9:16）被裁的是**上下**，方向不能报错 —— 报反了运营会去裁错边。 */
    @Test
    void tallImagesAreCroppedTopAndBottom() {
        ImageRatioAdvisor.Advice a = ImageRatioAdvisor.advise(new ImageSize(1080, 1920));

        assertThat(a.crop()).isEqualTo(Crop.TOP_BOTTOM);
        // 可见高度占 (1080/1920) / 0.75 = 0.75 ⇒ 共裁 25%。
        assertThat(a.totalPercent()).isEqualTo(25);
    }

    /** 刚好落在边界上不算超出（闭区间）。 */
    @Test
    void theRangeIsInclusiveOnBothEnds() {
        assertThat(ImageRatioAdvisor.advise(new ImageSize(134, 100)).warns()).isFalse();
        assertThat(ImageRatioAdvisor.advise(new ImageSize(75, 100)).warns()).isFalse();
    }

    /**
     * 🛡 尺寸测不出来时**不猜、也不警告**。
     *
     * <p>测不出尺寸的原因通常是格式冷门，而那和"这张图会不会被裁"是两件事。
     * 在这里瞎报一个警告，运营会去裁一张本来没问题的图。
     */
    @Test
    void unknownOrAbsurdSizesProduceNoWarning() {
        assertThat(ImageRatioAdvisor.advise(null).warns()).isFalse();
        assertThat(ImageRatioAdvisor.advise(new ImageSize(0, 100)).warns()).isFalse();
        assertThat(ImageRatioAdvisor.advise(new ImageSize(999999, 100)).warns()).isFalse();
    }
}
