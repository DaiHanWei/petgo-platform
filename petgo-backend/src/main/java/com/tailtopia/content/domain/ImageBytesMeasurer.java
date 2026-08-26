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
}
