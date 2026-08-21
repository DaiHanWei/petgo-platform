package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.media.MediaProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：商品图公开 URL 派生（Story 1.6 AC4）。
 *
 * <p>本类看住的是「拼 URL」这种<b>写错了也不报错、只是线上没图或一片 404</b> 的逻辑。
 */
class ShopImageUrlResolverTest {

    private static ShopImageUrlResolver resolverWithCdn(String cdnBase) {
        MediaProperties props = new MediaProperties();
        props.getOss().setCdnBaseUrl(cdnBase);
        return new ShopImageUrlResolver(props);
    }

    @Test
    @DisplayName("正常拼接：cdnBase + / + objectKey")
    void buildsPublicUrl() {
        var r = resolverWithCdn("https://cdn.petgo.example");
        assertThat(r.publicUrl("shop/abc/main.jpg"))
                .isEqualTo("https://cdn.petgo.example/shop/abc/main.jpg");
    }

    @Test
    @DisplayName("🔴 CDN base 未配置 → null（让前端降级到占位图）")
    void nullWhenCdnMissing() {
        assertThat(resolverWithCdn("").publicUrl("shop/abc/main.jpg")).isNull();
        assertThat(resolverWithCdn(null).publicUrl("shop/abc/main.jpg")).isNull();
    }

    @Test
    @DisplayName("🔴 绝不返回半截 URL——否则客户端会拿相对路径打自己域名，表现为一堆 404 而非「没有图」")
    void neverReturnsRelativePath() {
        String url = resolverWithCdn("").publicUrl("shop/abc/main.jpg");
        assertThat(url).isNull();

        String ok = resolverWithCdn("https://cdn.petgo.example").publicUrl("shop/abc/main.jpg");
        assertThat(ok).startsWith("https://");
    }

    @Test
    @DisplayName("objectKey 为空/空白 → null")
    void nullWhenKeyBlank() {
        var r = resolverWithCdn("https://cdn.petgo.example");
        assertThat(r.publicUrl(null)).isNull();
        assertThat(r.publicUrl("   ")).isNull();
    }

    @Test
    @DisplayName("斜杠规范化：cdnBase 带尾斜杠 / key 带首斜杠都不产生双斜杠")
    void normalizesSlashes() {
        assertThat(resolverWithCdn("https://cdn.x/").publicUrl("a/b.jpg"))
                .isEqualTo("https://cdn.x/a/b.jpg");
        assertThat(resolverWithCdn("https://cdn.x").publicUrl("/a/b.jpg"))
                .isEqualTo("https://cdn.x/a/b.jpg");
        assertThat(resolverWithCdn("https://cdn.x/").publicUrl("/a/b.jpg"))
                .isEqualTo("https://cdn.x/a/b.jpg");
    }

    @Test
    @DisplayName("批量：不可解析的元素被剔除，不留 null 混进图集")
    void batchDropsUnresolvable() {
        var r = resolverWithCdn("https://cdn.x");
        assertThat(r.publicUrls(List.of("a.jpg", "   ", "b.jpg")))
                .containsExactly("https://cdn.x/a.jpg", "https://cdn.x/b.jpg");
        assertThat(r.publicUrls(null)).isEmpty();
        assertThat(resolverWithCdn("").publicUrls(List.of("a.jpg"))).isEmpty();
    }
}
