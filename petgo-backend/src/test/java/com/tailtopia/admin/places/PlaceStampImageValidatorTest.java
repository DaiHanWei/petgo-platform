package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.places.service.PlaceStampImageValidator;
import com.tailtopia.shared.error.AppException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 1.4 · L0：专属章校验只做四项（PNG / 512×512 / ≤300KB / 透明通道），
 * <b>不校验颜色、不校验形状</b>（D-10）。
 */
class PlaceStampImageValidatorTest {

    // ---- 手工拼 PNG（块 = 长度 + 类型 + 数据 + CRC），只为让图头 / 块结构真实可读 ----

    private static byte[] chunk(String type, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(12 + data.length);
        b.putInt(data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        b.put(t).put(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    private static byte[] png(int w, int h, int colorType, boolean trns, int padBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) colorType).put((byte) 0).put((byte) 0).put((byte) 0);
        out.writeBytes(chunk("IHDR", ihdr.array()));
        if (colorType == 3) {
            out.writeBytes(chunk("PLTE", new byte[] {0, 0, 0, (byte) 255, 0, 0}));
        }
        if (padBytes > 0) {
            out.writeBytes(chunk("tEXt", new byte[padBytes]));
        }
        if (trns) {
            out.writeBytes(chunk("tRNS", colorType == 3 ? new byte[] {0} : new byte[colorType == 0 ? 2 : 6]));
        }
        out.writeBytes(chunk("IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01}));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static void ok(byte[] b) {
        assertThatCode(() -> PlaceStampImageValidator.validate(b, b.length)).doesNotThrowAnyException();
    }

    private static void rejected(byte[] b, String code) {
        assertThatThrownBy(() -> PlaceStampImageValidator.validate(b, b.length))
                .isInstanceOf(AppException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((AppException) e).getMessageCode())
                        .isEqualTo(code));
    }

    @Test
    void jpegRenamedToPngIsRejectedByMagicBytes() throws Exception {
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        rejected(out.toByteArray(), "admin.err.places.stampNotPng");
    }

    @Test
    void wrongDimensionsAreRejectedWithMeasuredSize() {
        rejected(png(511, 512, 6, false, 0), "admin.err.places.stampSize");
        rejected(png(513, 513, 6, false, 0), "admin.err.places.stampSize");
        assertThatThrownBy(() -> PlaceStampImageValidator.validate(png(511, 512, 6, false, 0), 100))
                .hasMessageContaining("511×512");
    }

    @Test
    void over300KbIsRejected() {
        byte[] big = png(512, 512, 6, false, 301 * 1024);
        rejected(big, "admin.err.places.stampTooLarge");
        assertThatThrownBy(() -> PlaceStampImageValidator.validate(big, big.length)).hasMessageContaining("KB");
    }

    @Test
    void rgbWithoutTrnsHasNoAlpha() {
        rejected(png(512, 512, 2, false, 0), "admin.err.places.stampNoAlpha");
        rejected(png(512, 512, 0, false, 0), "admin.err.places.stampNoAlpha");
        rejected(png(512, 512, 3, false, 0), "admin.err.places.stampNoAlpha");
    }

    @Test
    void alphaColorTypesAndTrnsPass() {
        ok(png(512, 512, 6, false, 0)); // RGBA
        ok(png(512, 512, 4, false, 0)); // 灰度 + alpha
        ok(png(512, 512, 3, true, 0));  // 调色板 + tRNS
        ok(png(512, 512, 2, true, 0));  // RGB + tRNS
    }

    /** 🔴 D-10：彩色、非圆形的 RGBA 章必须能过 —— 证明不校验颜色、不校验圆形安全区。 */
    @Test
    void colourfulNonCircularRgbaPasses() throws Exception {
        BufferedImage img = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(255, 0, 0, 255));
        g.fillRect(0, 0, 512, 170); // 顶到边：远超任何圆形安全区
        g.setColor(new Color(0, 180, 0, 200));
        g.fillPolygon(new int[] {0, 512, 256}, new int[] {512, 512, 200}, 3);
        g.setColor(new Color(0, 0, 255, 128));
        g.fillRect(400, 0, 112, 512);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        ok(out.toByteArray());
    }

    @Test
    void emptyOrGarbageIsNotPng() {
        rejected(new byte[0], "admin.err.places.stampNotPng");
        rejected("hello".getBytes(StandardCharsets.UTF_8), "admin.err.places.stampNotPng");
    }
}
