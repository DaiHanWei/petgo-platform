package com.tailtopia.content.service;

import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 发帖请求里每张图露出位置的<b>严格</b>校验（V1.3.4 Story 2.1 · AD-5 · FR-125）。
 *
 * <h2>🔴 刻意不复用 {@link ImageSizeResolver} 的「整组作废」</h2>
 * 尺寸丢了服务端还能异步兜底测量；露出位置丢了<b>没有任何兜底</b>，会无声退化为居中 ——
 * 用户在露出区域页的选择白做。新客户端是唯一写入方，长度不齐 / 越界只可能是客户端 bug，
 * 直接 422 暴露出来比吞掉好。
 *
 * <p>只校验形状（与 imageUrls 等长）与取值范围（{@code [0, 1]} 有限数），不判断「哪张图该有值」——
 * 服务端拿不到可靠宽高（{@code image_sizes} 可能待兜底）。数值原样保留，不四舍五入。
 *
 * <p>纯函数、无 IO、不打日志（校验失败的 ProblemDetail 已够，避免日志噪声）。
 */
@Component
public class ImageCropOffsetValidator {

    /**
     * @param imageUrls 请求里的图片列表（null 按长度 0 计）
     * @param offsets   请求里的露出位置（可空）
     * @return 应落库的值：未带 / 无图且空数组 → {@code null}；否则原数组的拷贝（保留 null 元素）
     * @throws AppException 422：长度不符或越界 / 非有限数
     */
    public List<Double> validate(List<String> imageUrls, List<Double> offsets) {
        if (offsets == null) {
            return null;
        }
        int imageCount = imageUrls == null ? 0 : imageUrls.size();
        if (imageCount == 0 && offsets.isEmpty()) {
            return null;
        }
        if (offsets.size() != imageCount) {
            throw AppException.validation("图片露出位置与图片数量不符");
        }
        for (Double v : offsets) {
            if (v != null && (!Double.isFinite(v) || v < 0 || v > 1)) {
                throw AppException.validation("图片露出位置超出范围");
            }
        }
        return new ArrayList<>(offsets);
    }
}
