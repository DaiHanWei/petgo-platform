package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tailtopia.content.domain.ImageSize;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 2.1（L0）：详情接口下发图片原始宽高（FR-113 · AD-A12）。
 *
 * <p>本 story 的存在本身是一处订正 —— PRD 把 FR-113 归为「纯前端」，但详情响应此前只有
 * {@code imageUrls}、没有尺寸（v1.1.6 AD-5 明写「既有五处图片读取点一处不动」，详情页正是其中之一）。
 * 没有这一条，Story 2.2 的「按原比例通栏」拿不到数据，实现者只能在「偷偷改后端 /
 * 偷偷现场测量 / 全走占位」之间自选。
 *
 * <p>钉的是**对齐**与**不越界**两件事：下标必须对得上（否则第 1 张图套用第 2 张的比例 = 图文不符），
 * 且服务端**只给原始宽高**（clamp 与高度护栏一律客户端算，两边都算就是双重裁切）。
 */
class DetailImageSizesTest {

    private final ImageSizeResolver resolver = new ImageSizeResolver();

    private static final List<String> TWO_IMAGES =
            List.of("https://cdn/1.jpg", "https://cdn/2.jpg");

    @Nested
    @DisplayName("AC1/AC2 同序等长与存量容错")
    class Alignment {

        @Test
        void sizesAreSameLengthAsImages() {
            List<ImageSize> out = resolver.alignForRead(
                    TWO_IMAGES, List.of(new ImageSize(1200, 900), new ImageSize(800, 800)));

            assertThat(out).hasSameSizeAs(TWO_IMAGES);
            assertThat(out.get(0)).isEqualTo(new ImageSize(1200, 900));
            assertThat(out.get(1)).isEqualTo(new ImageSize(800, 800));
        }

        /** AC1：某张测不出来 → 该位 {@code null} 占位，**下标仍对得上**。 */
        @Test
        void missingOneKeepsIndexAlignedWithNullPlaceholder() {
            List<ImageSize> out = resolver.alignForRead(
                    TWO_IMAGES, Arrays.asList(null, new ImageSize(800, 800)));

            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isNull();
            assertThat(out.get(1))
                    .as("第 2 张的尺寸必须仍落在下标 1 上，不能因为第 1 张缺失就前移")
                    .isEqualTo(new ImageSize(800, 800));
        }

        /** 🔴 AC2：V1.1.6 之前发布的存量内容整列为 null —— 不报错，补成等长的全 null。 */
        @Test
        void legacyContentWithNoSizeColumnYieldsAllNulls() {
            List<ImageSize> out = resolver.alignForRead(TWO_IMAGES, null);

            assertThat(out).hasSize(2).containsOnlyNulls();
        }

        @Test
        void legacyContentWithEmptyListYieldsAllNulls() {
            List<ImageSize> out = resolver.alignForRead(TWO_IMAGES, List.of());

            assertThat(out).hasSize(2).containsOnlyNulls();
        }

        /**
         * 库里存短了（历史脏数据 / 事后改过图）：按下标取到哪算哪，多出来的位置补 null。
         *
         * <p>刻意**不**像 {@code normalize} 那样整组作废 —— 那条规则针对的是**客户端上报**的数据
         * （长度不符意味着可能错位，错位会图文不符）；库里的数据是我们自己写的，
         * 按下标对齐比让整条内容都没有尺寸要好。
         */
        @Test
        void storedShorterThanImages_padsWithNull() {
            List<ImageSize> out =
                    resolver.alignForRead(TWO_IMAGES, List.of(new ImageSize(1200, 900)));

            assertThat(out).hasSize(2);
            assertThat(out.get(0)).isEqualTo(new ImageSize(1200, 900));
            assertThat(out.get(1)).isNull();
        }

        @Test
        void storedLongerThanImages_extraDropped() {
            List<ImageSize> out = resolver.alignForRead(
                    List.of("https://cdn/1.jpg"),
                    List.of(new ImageSize(1200, 900), new ImageSize(800, 800)));

            assertThat(out).hasSize(1).containsExactly(new ImageSize(1200, 900));
        }

        /** 荒唐尺寸（多半是算错或手填）当作测不出来，走客户端占位兜底。 */
        @Test
        void unreasonableSizeIsTreatedAsMissing() {
            List<ImageSize> out = resolver.alignForRead(
                    TWO_IMAGES,
                    Arrays.asList(new ImageSize(0, 900), new ImageSize(99999, 99999)));

            assertThat(out).hasSize(2).containsOnlyNulls();
        }

        /** 纯文字帖不需要这一列占位 —— 返回 null，NON_NULL 下整个字段省略。 */
        @Test
        void textOnlyPostGetsNoSizeArrayAtAll() {
            assertThat(resolver.alignForRead(null, null)).isNull();
            assertThat(resolver.alignForRead(List.of(), null)).isNull();
        }
    }

    @Nested
    @DisplayName("AC3/AC4 只下发原始宽高，数据来源唯一")
    class ShapeGuard {

        // ImageSize 的两个字段都是原始 int，不存在 null —— 不需要 NON_NULL 配置，
        // 默认 mapper 反而更能暴露「多出来的幽灵字段」（record 上加 isXxx() 就会冒出来）。
        private final JsonMapper json = JsonMapper.builder().build();

        /**
         * 🔴 AC3：线格式上**只有 w / h**。
         *
         * <p>服务端先 clamp 一遍、客户端再 clamp 一遍就是**双重裁切**（v1.1.6 AD-6 Rule 6）；
         * 而高度护栏依赖可视区高度，服务端根本算不了。所以「比例」「已算好的高度」
         * 一个都不许出现在线上。
         */
        @Test
        @SuppressWarnings("unchecked")
        void wireFormatCarriesOnlyRawWidthAndHeight() {
            Map<String, Object> m = json.convertValue(new ImageSize(1200, 900), Map.class);

            assertThat(m.keySet()).isEqualTo(java.util.Set.of("w", "h"));
            assertThat(m).doesNotContainKeys("ratio", "aspectRatio", "displayHeight", "height");
        }

        /**
         * AC4：尺寸只能来自 v1.1.6 那一列，**不新增测量路径、不现场测量**。
         *
         * <p>母版假设 A-3「单帖实时加载可现场取尺寸」不采纳（AD-A12.2）：现场测量要等图片头部
         * 下载完才知道留多高，比现状更抖，且与 Feed 数据来源分叉。
         * 本断言用签名钉住 —— 对齐方法只吃「已存的那一列」，拿不到任何可供测量的东西。
         */
        @Test
        void alignForReadTakesOnlyStoredColumn_noMeasurementHook() throws NoSuchMethodException {
            var m = ImageSizeResolver.class.getDeclaredMethod(
                    "alignForRead", List.class, List.class);

            assertThat(m.getReturnType()).isEqualTo(List.class);
            // 两个入参就是「图片地址」与「库里存的尺寸」；没有 InputStream / URL fetcher 之类的口子。
            assertThat(m.getParameterCount()).isEqualTo(2);
        }
    }
}
