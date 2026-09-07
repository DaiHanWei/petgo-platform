package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dto.SeedPostForm;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ImageSize;
import org.junit.jupiter.api.Test;

/**
 * L0：表单把「上传时量到的宽高」交给服务端之前的归一（V1.1.6 Story 12.2）。
 *
 * <h2>🔴 这个类为什么单独存在</h2>
 * 「长度不符 ⇒ 整组作废」这条规则的**权威实现在 {@code ImageSizeResolver#normalize}**，
 * 所以端到端测试无论表单这层做不做归一都会绿 —— 我做反证时正是这么发现的：
 * 把表单里那句长度校验删掉，L1 用例<b>照样通过</b>。
 *
 * <p>那一层仍然要保留，但理由不是"多一层保险"，而是**为了日志的可信度**：
 * normalize 在长度不符时会打 WARN 说「客户端算错了长度，属实现 bug 而非用户行为」，
 * 而后台这一侧长度不符<b>恰恰是用户行为</b>（运营在兜底 URL 框里多填了一行）。
 * 表单先归一成 null，normalize 就走"没报尺寸"那条静默分支。
 *
 * <p>既然反证证明 L1 钉不住它，就在这里钉。
 */
class SeedPostFormImageSizesTest {

    private static SeedPostForm form(String urls, String sizes) {
        SeedPostForm f = new SeedPostForm();
        f.setType(ContentType.DAILY);
        f.setImageUrlsRaw(urls);
        f.setImageSizesRaw(sizes);
        return f;
    }

    @Test
    void sameLengthIsPassedThrough() {
        assertThat(form("https://a\nhttps://b", "1200x900\n800x800").imageSizes())
                .containsExactly(new ImageSize(1200, 900), new ImageSize(800, 800));
    }

    /** 🛡 短了 ⇒ 整组 null（**不是**把剩下的凑上去 —— 那会让第 2 张套上第 3 张的比例）。 */
    @Test
    void tooFewSizesYieldNull() {
        assertThat(form("https://a\nhttps://b", "1200x900").imageSizes()).isNull();
    }

    /** 🛡 多了同样整组 null —— 多出来的那一行说明两个字段已经不同步了。 */
    @Test
    void tooManySizesYieldNull() {
        assertThat(form("https://a", "1200x900\n800x800").imageSizes()).isNull();
    }

    /** 解析不了的一行 ⇒ 整组 null。⚠️ 绝不"跳过这一行"，那正是错位的来源。 */
    @Test
    void unparseableLineYieldsNull() {
        assertThat(form("https://a\nhttps://b", "1200x900\n800xABC").imageSizes()).isNull();
        assertThat(form("https://a", "1200").imageSizes()).isNull();
    }

    /** 没图 ⇒ null（纯文字帖不需要这一列占位，与 ImageSizeResolver 同口径）。 */
    @Test
    void noImagesYieldNull() {
        assertThat(form("", "1200x900").imageSizes()).isNull();
        assertThat(form("https://a", "  ").imageSizes()).isNull();
    }
}
