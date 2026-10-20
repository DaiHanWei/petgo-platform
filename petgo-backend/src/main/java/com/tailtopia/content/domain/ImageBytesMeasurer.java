package com.tailtopia.content.domain;

import java.awt.Dimension;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 从**字节**读图片宽高（V1.1.6 Story 11.5 从 {@code AdminSeedImageService} 提出）。
 *
 * <p>提出来的理由：Story 11.5 的标签图标上传也要量宽高，而它原先是
 * {@code AdminSeedImageService} 的包内静态方法 —— 让 {@code admin.tagicon} 去依赖
 * {@code admin.seed} 只为拿一个工具函数，方向不对；把那个方法放宽成 {@code public}
 * 又等于把一个内部实现细节写进那个服务的对外 API。
 *
 * <p>⚠️ 与 {@code ImageSizeBackfillService#measure(String)} <b>不是同一个函数</b>：
 * 那个读**远程 URL**（带超时、走网络），这个读**已在手里的字节**。
 * 两者注释里说的"同源"指做法相同（只读图头、不解码整张），不是同一份代码 —— 别去合并它们。
 *
 * <p>🔴 <b>只读图头，不解码整张图</b>：一张手机照片好几 MB，为了两个整数把它整个解出来是浪费。
 */
public final class ImageBytesMeasurer {

    private ImageBytesMeasurer() {
    }

    /**
     * 量宽高。
     *
     * <p>测不出来（格式冷门 / 不是图片 / 数值不像真实照片）返回 {@code null}。
     * 🛡 <b>「返回 null 之后怎么办」由调用方决定，本方法不做判断</b> ——
     * 种子内容那条链路是放过（Feed 侧有占位兜底），标签图标那条是拦住（尺寸校验是它的核心）。
     */
    public static ImageSize measure(byte[] bytes) {
        // 🔴 JDK 自带 ImageIO 没有 WebP 读取器（Java 21 实测：JPG/PNG/GIF/BMP/TIFF/WBMP），WebP 一律读不出 →
        //    后台图标（PRD：PNG / WebP）真 WebP 文件被判「测不出尺寸」拒收。WebP 宽高就在文件头固定位置，直接读；
        //    不引 ImageIO 插件（Spring Boot 胖 jar 里插件注册不稳）。
        if (isWebp(bytes)) {
            ImageSize size = measureWebp(bytes);
            return size != null && size.isReasonable() ? size : null;
        }
        ImageReader reader = null;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
                ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(iis, true, true);
            Dimension d = new Dimension(reader.getWidth(0), reader.getHeight(0));
            ImageSize size = new ImageSize(d.width, d.height);
            return size.isReasonable() ? size : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** RIFF....WEBP 容器头。 */
    static boolean isWebp(byte[] b) {
        return b != null && b.length >= 16
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /**
     * 读 WebP 首个块的画布宽高（Google WebP 容器规范）：
     * <ul>
     *   <li>{@code VP8X}（扩展：透明 / 动图 / 元数据）：偏移 24、27 各 3 字节小端 = 宽 − 1、高 − 1</li>
     *   <li>{@code VP8 }（有损）：偏移 23 起关键帧起始码 {@code 9D 01 2A}，偏移 26、28 各 2 字节小端，低 14 位为宽、高</li>
     *   <li>{@code VP8L}（无损）：偏移 20 签名 {@code 0x2F}，其后 28 位为 (宽 − 1, 高 − 1) 各 14 位</li>
     * </ul>
     * 头不完整 / 块类型不认识 → {@code null}。
     */
    static ImageSize measureWebp(byte[] b) {
        if (b.length < 30) {
            return null;
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X": {
                int w = 1 + u8(b, 24) + (u8(b, 25) << 8) + (u8(b, 26) << 16);
                int h = 1 + u8(b, 27) + (u8(b, 28) << 8) + (u8(b, 29) << 16);
                return new ImageSize(w, h);
            }
            case "VP8 ": {
                if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) {
                    return null;
                }
                int w = (u8(b, 26) | (u8(b, 27) << 8)) & 0x3FFF;
                int h = (u8(b, 28) | (u8(b, 29) << 8)) & 0x3FFF;
                return new ImageSize(w, h);
            }
            case "VP8L": {
                if (b.length < 25 || u8(b, 20) != 0x2F) {
                    return null;
                }
                int bits = u8(b, 21) | (u8(b, 22) << 8) | (u8(b, 23) << 16) | (u8(b, 24) << 24);
                int w = 1 + (bits & 0x3FFF);
                int h = 1 + ((bits >>> 14) & 0x3FFF);
                return new ImageSize(w, h);
            }
            default:
                return null;
        }
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }
}
