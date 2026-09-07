package com.tailtopia.admin.shop.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：商品表单里**图集原始串**的解析（R-2 回归）。
 *
 * <h2>为什么单独测这一段</h2>
 * 后台商品表单的多图上传控件（2026-08-27 起）把缩略图顺序写回一个 textarea，
 * 而 {@code galleryKeysRaw} 是**真正提交给服务端的那个字段** ——
 * 上传控件只是它的可视化编辑器（模板注释原话）。所以这段解析同时承载两条输入路径：
 * 控件回填的换行串，以及运营在折叠兜底区里手填的（可能用逗号）。
 *
 * <p>⚠️ 此前这段**零覆盖**：仓库里与图集有关的用例只有服务层那条「超 8 张拒绝」，
 * 解析本身（分隔符、trim、空行）从没被验证过。而它出错的表现是
 * <b>商品少一张图或多一个空 key</b> —— 页面照常渲染，没有任何报错。
 */
class ShopProductFormTest {

    private static List<String> parse(String raw) {
        ShopProductForm f = new ShopProductForm();
        f.setGalleryKeysRaw(raw);
        return f.galleryKeys();
    }

    @Test
    @DisplayName("换行分隔（上传控件回填的形态）")
    void splitsOnNewline() {
        assertThat(parse("a.jpg\nb.jpg\nc.jpg"))
                .containsExactly("a.jpg", "b.jpg", "c.jpg");
    }

    @Test
    @DisplayName("逗号分隔（手填兜底区常见形态）")
    void splitsOnComma() {
        assertThat(parse("a.jpg,b.jpg")).containsExactly("a.jpg", "b.jpg");
    }

    @Test
    @DisplayName("🔴 顺序即展示顺序 —— 解析不得重排")
    void keepsOrder() {
        // App 的 _gallery() 是 [mainImageUrl, ...galleryUrls] 直接喂 PageView，
        // 这里一乱，用户左右滑看到的顺序就跟运营在后台排的不是一回事。
        assertThat(parse("z.jpg\na.jpg\nm.jpg"))
                .containsExactly("z.jpg", "a.jpg", "m.jpg");
    }

    @Test
    @DisplayName("🔴 空行与空白被丢掉，不产出空 key")
    void dropsBlanks() {
        // 空 key 会拼出一个 `<cdn>/` 的坏 URL，App 端表现为图集里多出一张裂图。
        assertThat(parse("a.jpg\n\n  \nb.jpg\n")).containsExactly("a.jpg", "b.jpg");
    }

    @Test
    @DisplayName("两端空白 trim（粘贴 key 时最容易带进来）")
    void trimsEachEntry() {
        assertThat(parse("  a.jpg  ,\tb.jpg ")).containsExactly("a.jpg", "b.jpg");
    }

    @Test
    @DisplayName("null / 全空白 → 空列表（不是 null，调用方直接迭代）")
    void blankGivesEmptyList() {
        assertThat(parse(null)).isEmpty();
        assertThat(parse("   \n  ")).isEmpty();
    }
}
