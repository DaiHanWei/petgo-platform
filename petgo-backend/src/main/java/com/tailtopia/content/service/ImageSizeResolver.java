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

    /**
     * 读路径的对齐（V1.3.0 批次 A · Story 2.1 · AD-A12）：把**库里存着的**尺寸列对齐到图片数组。
     *
     * <p>与 {@link #normalize} 的区别在于「谁在说话」：`normalize` 处理的是**客户端上报**的数据
     * （可能算错、可能撒谎，所以长度不符要整组作废）；本方法处理的是**自己库里**的数据，
     * 只需要补齐下标，不需要不信任。
     *
     * <p>为什么要补齐而不是原样下发：存量内容（V1.1.6 之前发布的）整列是 {@code null}，
     * 而详情页要**在图片加载完成前就留对高度**，客户端按下标取尺寸。给它一个长度对得上、
     * 缺失位为 {@code null} 的数组，比给它一个 {@code null} 让它自己判空再判长度要少一个出错点。
     *
     * <p>元素类型与 Feed 侧**完全一致**（{@link ImageSize}，只有原始宽高），客户端两处共用一套解析。
     *
     * @param imageUrls 这条内容的图片列表（可空）
     * @param stored    库里的尺寸列（可空、可短、可长）
     * @return 与 {@code imageUrls} 同序等长、缺失位为 {@code null} 的列表；无图时返回 {@code null}
     */
    public List<ImageSize> alignForRead(List<String> imageUrls, List<ImageSize> stored) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null; // 纯文字帖不需要这一列占位
        }
        int n = imageUrls.size();
        if (stored == null || stored.isEmpty()) {
            return nulls(n); // 存量内容：整列为空 → 全 null 占位，下标仍对得上
        }
        List<ImageSize> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 长度对不上（历史脏数据 / 事后改过图）时只取得到的那部分，多出来的丢掉。
            // 这里刻意**不**像 normalize 那样整组作废：库里的数据是我们自己写的，
            // 按下标取到哪算哪，比整条内容都没有尺寸要好。
            ImageSize s = i < stored.size() ? stored.get(i) : null;
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
