package com.tailtopia.admin.places.service;

import com.tailtopia.content.domain.ImageBytesMeasurer;
import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;

/**
 * 场所专属章上传校验（V1.3.2 Story 1.4 · AB-18B · 决策 D-10 · AD-18）。纯函数，不碰网络。
 *
 * <p>🔴 <b>只做四项</b>：PNG（文件头魔数）/ 512×512 / ≤200KB / 带透明通道。任一不过 → 422，文案给出具体原因与实测值。
 * <b>不校验颜色、不校验形状 / 圆形安全区</b>：D-10 作废了 PRD §3.3 与后台 AB-18B 的「单色 + 客户端着色 + 圆形安全区」——
 * 章按原图原色展示，章不一定是圆的。测试里专门放了一张彩色非圆形 RGBA 图证明它能过。
 *
 * <p>透明通道判定（不解码像素，只扫块头；块结构 = 4 字节长度 + 4 字节类型 + 数据 + 4 字节 CRC）：
 * IHDR color type ∈ {4 灰度+alpha, 6 RGBA} 直接合格；∈ {0 灰度, 2 RGB, 3 调色板} 需在 IDAT 之前出现 {@code tRNS}。
 */
public final class PlaceStampImageValidator {

    /** 规格：正方形边长。 */
    public static final int SIDE_PX = 512;
    /** 规格：单文件上限（字节）。 */
    public static final long MAX_BYTES = 200L * 1024;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private PlaceStampImageValidator() {
    }

    /**
     * @param bytes 文件字节
     * @param size  文件大小（multipart 报告值；以它与 bytes.length 的较大者为准，防报小）
     */
    public static void validate(byte[] bytes, long size) {
        if (bytes == null || !isPng(bytes)) {
            throw AppException.validation("只支持 PNG").code("admin.err.places.stampNotPng");
        }
        long actual = Math.max(size, bytes.length);
        if (actual > MAX_BYTES) {
            long kb = (actual + 1023) / 1024;
            throw AppException.validation("单个文件须 ≤200KB，当前 " + kb + "KB")
                    .code("admin.err.places.stampTooLarge", kb);
        }
        com.tailtopia.content.domain.ImageSize dim = ImageBytesMeasurer.measure(bytes);
        if (dim == null) {
            throw AppException.validation("无法读取图片尺寸").code("admin.err.places.stampUnmeasurable");
        }
        if (dim.w() != SIDE_PX || dim.h() != SIDE_PX) {
            throw AppException.validation("须为 512×512，当前 " + dim.w() + "×" + dim.h())
                    .code("admin.err.places.stampSize", dim.w(), dim.h());
        }
        if (!hasAlpha(bytes)) {
            throw AppException.validation("须为透明底 PNG（未检测到透明通道）").code("admin.err.places.stampNoAlpha");
        }
    }

    static boolean isPng(byte[] b) {
        if (b.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (b[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** 扫块头判透明通道（调用前已确认是 PNG）。 */
    static boolean hasAlpha(byte[] b) {
        int pos = PNG_MAGIC.length;
        Integer colorType = null;
        while (pos + 8 <= b.length) {
            long len = readUInt(b, pos);
            String type = new String(b, pos + 4, 4, StandardCharsets.US_ASCII);
            int data = pos + 8;
            if (len < 0 || data + len > b.length) {
                return false; // 截断 / 损坏：宁可拒
            }
            switch (type) {
                case "IHDR" -> {
                    if (len < 13) {
                        return false;
                    }
                    colorType = b[data + 9] & 0xFF;
                    if (colorType == 4 || colorType == 6) {
                        return true;
                    }
                }
                case "tRNS" -> {
                    return colorType != null && (colorType == 0 || colorType == 2 || colorType == 3);
                }
                case "IDAT", "IEND" -> {
                    return false; // tRNS 必须在 IDAT 之前
                }
                default -> {
                    // 其它辅助块跳过
                }
            }
            pos = (int) (data + len + 4); // + CRC
        }
        return false;
    }

    private static long readUInt(byte[] b, int at) {
        return ((long) (b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
