package com.tailtopia.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * WebP 宽高读取：JDK ImageIO 没有 WebP 读取器，由 {@link ImageBytesMeasurer} 按容器规范读文件头。
 * 夹具是真实 WebP 文件（PIL 生成，覆盖有损 VP8 / 无损 VP8L / 扩展 VP8X 透明与动图），不是改了扩展名的 PNG。
 */
class ImageBytesMeasurerWebpTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ImageBytesMeasurerWebpTest.class.getResourceAsStream("/fixtures/webp/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void measuresAllThreeWebpChunkTypes() throws IOException {
        assertThat(ImageBytesMeasurer.measure(fixture("lossy-112x96.webp"))).isEqualTo(new ImageSize(112, 96));
        assertThat(ImageBytesMeasurer.measure(fixture("lossless-300x200.webp"))).isEqualTo(new ImageSize(300, 200));
        assertThat(ImageBytesMeasurer.measure(fixture("alpha-336x336.webp"))).isEqualTo(new ImageSize(336, 336));
        assertThat(ImageBytesMeasurer.measure(fixture("animated-64x48.webp"))).isEqualTo(new ImageSize(64, 48));
    }

    @Test
    void truncatedOrUnknownWebpIsNull() throws IOException {
        byte[] lossy = fixture("lossy-112x96.webp");
        assertThat(ImageBytesMeasurer.measure(Arrays.copyOf(lossy, 20))).as("头不完整").isNull();
        byte[] unknown = lossy.clone();
        unknown[12] = 'X';
        assertThat(ImageBytesMeasurer.measure(unknown)).as("块类型不认识").isNull();
        byte[] badStart = lossy.clone();
        badStart[23] = 0;
        assertThat(ImageBytesMeasurer.measure(badStart)).as("VP8 起始码不对").isNull();
    }
}
