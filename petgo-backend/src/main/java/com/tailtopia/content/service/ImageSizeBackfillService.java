package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ImageSize;
import com.tailtopia.content.repository.ContentPostRepository;
import java.awt.Dimension;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 图片尺寸的<b>服务端兜底测量</b>（V1.1.6 Story 3.1 · AD-5 Rule 3）。
 *
 * <p>两个写入端都必须产出尺寸：App 发布由客户端上报（判容差区间时尺寸已在手），
 * <b>缺失或明显不合理时由这里补</b>；后台种子发布没有客户端，<b>一律</b>走这里。
 *
 * <h2>🔴 为什么是异步，而不是发布时同步测量</h2>
 * 发布是一次事务操作。一条内容最多 9 张图 —— 同步测量就是把 <b>9 次网络往返压进事务里</b>，
 * 长时间占着数据库连接；对象存储抽风时，发布会跟着卡住甚至超时回滚。
 * <b>为了一个「锦上添花」的字段，赌上「发布成功」这件事，不划算。</b>
 *
 * <p>走 {@code @Async} 事后补齐符合架构护栏（异步只用 {@code @Async} + DB 状态机，禁引 MQ）。
 *
 * <h2>⚠️ 代价：刚发布的帖可能短暂没有尺寸</h2>
 * 这是<b>可接受的</b>，因为「存量内容永远没有尺寸」本来就成立（零回填是硬要求），
 * 客户端的加载期占位策略<b>本就是必做项</b>（AD-6 Rule 4）——
 * 不是为这个代价临时加的补丁。
 *
 * <h2>🛡 绝不阻断发布</h2>
 * 测不出来就是 {@code null}，一切异常吞掉只记日志。没有重试、没有队列、没有状态机 ——
 * 这个字段不值得那些复杂度。
 */
@Service
public class ImageSizeBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ImageSizeBackfillService.class);

    /**
     * 单张图的读取超时。
     *
     * <p>只读图头就够了（见下），所以给得很短 —— 慢就放弃，不要为了一个装饰性字段
     * 把线程挂在半开连接上。
     */
    private static final int TIMEOUT_MS = 3000;

    private final ContentPostRepository posts;

    public ImageSizeBackfillService(ContentPostRepository posts) {
        this.posts = posts;
    }

    /**
     * 异步补齐某条内容缺失的图片尺寸。
     *
     * <p>⚠️ 只补 {@code null} 的位置，<b>不覆盖客户端已上报且合理的值</b> ——
     * 客户端拿到的是原图尺寸，比我们隔着网络读回来的更可信。
     */
    /**
     * ⚠️ 本方法<b>刻意不开事务</b>（只挂 {@code @Async}）。
     *
     * <p>此前是 {@code @Async @Transactional} 同挂：同一事务里先装载实体、再做最多
     * 9×(3s+3s) 的 OSS 网络读、最后靠脏检查<b>整行</b> UPDATE 收尾 —— 期间审核 / 下架若并发
     * 改了 {@code status} / {@code report_hidden_at} / {@code deleted_at}，收尾会拿装载时的
     * <b>旧值把它们静默盖掉</b>（本表无 {@code @DynamicUpdate} 无 {@code @Version}；
     * Lark 定时发帖修过同款竞态）。
     *
     * <p>现在：① OSS 网络读全部在<b>事务外</b>做（{@code findById} 用仓储自带的短只读事务，
     * 返回后实体即脱管，慢网络不占连接不占事务）；② 落库走
     * {@code ContentPostRepository#updateImageSizes} 的<b>定向单列</b> UPDATE，
     * 只动 {@code image_sizes}（+ {@code updated_at}），别的列碰都不碰 ——
     * 并发提交的审核 / 下架结果不再可能被这里撤销。
     */
    @Async
    public void backfill(long postId) {
        try {
            doBackfill(postId);
        } catch (RuntimeException e) {
            // 兜底测量失败不是业务错误：内容已经发布成功了，这里只是没补上尺寸。
            log.warn("图片尺寸兜底测量失败 postId={}: {}", postId, e.toString());
        }
    }

    private void doBackfill(long postId) {
        // 无事务装载：只为拿 imageUrls / 客户端已报的尺寸，读完实体即脱管（detached），
        // 下面的逐张网络测量不占任何事务。
        ContentPost post = posts.findById(postId).orElse(null);
        if (post == null || post.getImageUrls() == null || post.getImageUrls().isEmpty()) {
            return;
        }
        List<String> urls = post.getImageUrls();
        List<ImageSize> current = post.getImageSizes();
        List<ImageSize> out = new ArrayList<>(urls.size());
        boolean changed = false;
        for (int i = 0; i < urls.size(); i++) {
            ImageSize known = (current != null && i < current.size()) ? current.get(i) : null;
            if (known != null) {
                out.add(known); // 客户端报的原图尺寸更可信，不覆盖
                continue;
            }
            ImageSize measured = measure(urls.get(i));
            out.add(measured);
            changed |= measured != null;
        }
        if (changed) {
            // 🛡 定向单列落库（绝不 save 整行）：并发的审核 / 下架写入不受影响。
            posts.updateImageSizes(postId, out, Instant.now());
        }
    }

    /**
     * 读远程图片的宽高。
     *
     * <p>⚠️ <b>只读图头，不下载整张图</b>：用流式读取器拿宽高，读到就停。
     * 一张手机照片好几 MB，为了两个整数把它整个拉下来是浪费 —— 尤其这是异步批量路径。
     *
     * <p>测不出来（网络失败 / 不是图片 / 格式不认识）一律返回 {@code null}，由调用方按缺失处理。
     */
    ImageSize measure(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        ImageReader reader = null;
        try {
            URLConnection conn = URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            try (InputStream in = conn.getInputStream();
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
            }
        } catch (Exception e) {
            // 包括本地/测试环境图片不可达的情况 —— 静默降级，绝不让它影响发布或测试。
            log.debug("读取图片尺寸失败 url={}: {}", url, e.toString());
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }
}
