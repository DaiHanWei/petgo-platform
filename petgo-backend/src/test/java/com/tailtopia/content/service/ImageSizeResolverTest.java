package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.domain.ImageSize;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L0：图片尺寸的采信与归一（V1.1.6 Story 3.1 · AD-5 Rule 2）。
 *
 * <p>核心是一条判断：<b>什么时候该相信客户端报上来的尺寸</b>。
 * 尺寸数组与图片数组必须同序等长 —— 而<b>错位的后果是图文不符</b>
 * （第 1 张图套用第 2 张的比例），比"没有尺寸"严重得多：
 * 后者有客户端占位兜底，前者是显示错误。
 */
class ImageSizeResolverTest {

    private final ImageSizeResolver resolver = new ImageSizeResolver();

    @Test
    void noImagesMeansNoSizeColumnAtAll() {
        assertThat(resolver.normalize(null, null)).isNull();
        assertThat(resolver.normalize(List.of(), null)).isNull();
    }

    @Test
    void nothingReportedYieldsAlignedNullsForBackfill() {
        List<ImageSize> out = resolver.normalize(List.of("a", "b", "c"), null);

        assertThat(out).as("必须与图片数同长，用 null 占位保持下标对齐").hasSize(3);
        assertThat(out).containsExactly(null, null, null);
        assertThat(resolver.needsBackfill(out)).isTrue();
    }

    /**
     * 🛡 <b>本类最要紧的一条</b>：长度对不上 → <b>整组作废</b>，不做部分采信。
     *
     * <p>长度不符时无法判断是"少传了哪一张"还是"顺序错了"。
     * 猜错的代价是图文不符，所以宁可全部丢掉重新测。
     */
    @Test
    void lengthMismatchDiscardsTheWholeBatch() {
        List<ImageSize> reported = List.of(new ImageSize(100, 200), new ImageSize(300, 400));

        List<ImageSize> out = resolver.normalize(List.of("a", "b", "c"), reported);

        assertThat(out).as("长度不符必须整组作废 —— 部分采信会导致错位，而错位是图文不符")
                .containsExactly(null, null, null);
    }

    /** 长度对得上时，单张不合理只作废那一张（下标可靠，不会错位）。 */
    @Test
    void unreasonableSingleEntryIsDroppedButOthersKept() {
        List<ImageSize> reported = Arrays.asList(
                new ImageSize(1200, 1600),
                new ImageSize(0, 100),                                   // 宽为 0
                new ImageSize(ImageSize.MAX_REASONABLE_PX + 1, 100),     // 大到不像真实照片
                null);                                                   // 客户端自己就没测出来

        List<ImageSize> out = resolver.normalize(List.of("a", "b", "c", "d"), reported);

        assertThat(out).hasSize(4);
        assertThat(out.get(0)).isEqualTo(new ImageSize(1200, 1600));
        assertThat(out.get(1)).isNull();
        assertThat(out.get(2)).isNull();
        assertThat(out.get(3)).isNull();
        assertThat(resolver.needsBackfill(out)).isTrue();
    }

    @Test
    void fullyValidBatchNeedsNoBackfill() {
        List<ImageSize> reported = List.of(new ImageSize(1200, 1600), new ImageSize(800, 800));

        List<ImageSize> out = resolver.normalize(List.of("a", "b"), reported);

        assertThat(out).containsExactly(new ImageSize(1200, 1600), new ImageSize(800, 800));
        assertThat(resolver.needsBackfill(out)).isFalse();
    }
}
