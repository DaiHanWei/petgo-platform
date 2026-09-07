package com.tailtopia.content.service;

import com.tailtopia.content.domain.ImageSize;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 图片原始宽高的<b>采信与归一</b>（V1.1.6 Story 3.1 · AD-5 Rule 2/3）。
 *
 * <p>本类只负责「客户端报上来的这组尺寸能不能用」，<b>不负责测量</b>
 * （测量是 {@code ImageSizeBackfillService} 的事，且异步）。
 *
 * <h2>🛡 长度对不上 → 整组作废，不做部分采信</h2>
 * 尺寸数组与图片数组必须<b>同序等长</b>。一旦长度对不上，就无法判断是「少传了哪一张」
 * 还是「顺序错位」—— 而<b>错位的后果是图文不符</b>（第 1 张图套用第 2 张的比例）。
 *
 * <p>缺失只是没有尺寸（客户端有加载期占位兜底），错位是<b>显示错误</b>。
 * 所以宁可整组丢掉重新测，也不猜。
 */
@Component
public class ImageSizeResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageSizeResolver.class);

    /**
     * 归一化客户端上报的尺寸。
     *
     * @param imageUrls  这条内容的图片列表（可空）
     * @param reported   客户端上报的尺寸（可空）
     * @return 与 {@code imageUrls} <b>同序等长</b>的列表；测不出 / 不可信的位置为 {@code null}。
     *         无图时返回 {@code null}（而不是空列表）—— 纯文字帖不需要这一列占位。
     */
    public List<ImageSize> normalize(List<String> imageUrls, List<ImageSize> reported) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }
        int n = imageUrls.size();
        if (reported == null || reported.isEmpty()) {
            return nulls(n); // 没报 → 全部交给异步兜底
        }
        if (reported.size() != n) {
            // 🛡 整组作废。这条日志值得留：它意味着客户端算错了长度，属实现 bug 而非用户行为。
            log.warn("图片尺寸长度与图片数不符，整组作废交由兜底测量: images={} sizes={}",
                    n, reported.size());
            return nulls(n);
        }
        List<ImageSize> out = new ArrayList<>(n);
        for (ImageSize s : reported) {
            // 明显不合理的单张作废（其余仍采信）—— 这与「长度不符」不同：
            // 长度对得上时下标是可靠的，某一张不可信只影响那一张，不会错位。
            out.add(s != null && s.isReasonable() ? s : null);
        }
        return out;
    }

    /** 是否还有位置没有尺寸（决定要不要排异步兜底）。 */
    public boolean needsBackfill(List<ImageSize> sizes) {
        return sizes != null && sizes.stream().anyMatch(s -> s == null);
    }

    private static List<ImageSize> nulls(int n) {
        return new ArrayList<>(Collections.nCopies(n, null));
    }
}
