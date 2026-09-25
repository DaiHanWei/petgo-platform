package com.tailtopia.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.dto.ShopProductPageResponse;
import com.tailtopia.shop.dto.ShopProductSummaryView;
import com.tailtopia.shop.service.ShopProductQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：商品列表端点的<b>两种返回形态</b>（Story 4-5 AC2 · SHOP-NFR-04）。
 *
 * <p>🔴🔴 <b>本类保护的是「线上已经装在用户手机上的老版本 App」。</b>
 *
 * <p>那些版本期望这个端点返回一个 JSON <b>数组</b>。改成分页信封它们会解析失败 ——
 * 轻则只看到第一页，重则整页打不开。<b>发版救不了已经装好的那些</b>：
 * 用户不升级，这个端点对他们就是坏的。
 *
 * <p>所以「不带分页参数 → 仍返回全量数组」不是一个可选的体面做法，是硬性要求。
 * <b>删除这个类，等于删掉那条兼容性保证本身。</b>
 *
 * <p>本类直接测 Controller 的分派 + <b>真实 Jackson 序列化</b>：
 * 只断言「调了哪个 service 方法」不够 —— 老版本 App 看到的是<b>字节</b>，
 * 所以必须断言序列化出来的第一个字符是 {@code [} 而不是 {@code &#123;}。
 */
class ShopProductListLegacyShapeTest {

    private ShopProductQueryService query;
    private ShopProductController controller;

    /** 镜像生产的 NON_NULL 配置。 */
    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @BeforeEach
    void setUp() {
        query = Mockito.mock(ShopProductQueryService.class);
        controller = new ShopProductController(query);
    }

    private static ShopProductSummaryView view(String token) {
        return new ShopProductSummaryView(token, "Royal Canin", "Royal Canin",
                ProductCategory.MAKANAN, "shop/p/main.jpg", "https://cdn.test/shop/p/main.jpg",
                800, 800, Species.DOG, 285_000L);
    }

    // ---------- 🔴 老版本：不带任何分页参数 ----------

    @Test
    @DisplayName("🔴🔴 线上老版本（从不传 cursor/size）拿到的仍是**全量 JSON 数组**")
    void legacyClientNeverSendingPagingParamsStillGetsAPlainArray() {
        when(query.list(isNull(), isNull())).thenReturn(List.of(view("tokA"), view("tokB")));

        Object body = controller.list(null, null, null, null).getBody();

        // ① 走的是全量路径，一次分页查询都没打。
        verify(query).list(isNull(), isNull());
        verify(query, never()).page(any(), any(), any(), any());

        // ② 🔴 更要紧的是**线上的字节形态**：老版本 App 解析的是数组。
        //    只断言「调了 list()」不够 —— 有人把返回值包成信封，那条断言照样绿。
        String wire = json.writeValueAsString(body);
        assertThat(wire)
                .as("老版本 App 期望 JSON 数组；返回对象会让它整页打不开，而发版救不了它")
                .startsWith("[")
                .doesNotContain("\"items\"")
                .doesNotContain("\"hasMore\"")
                .doesNotContain("\"nextCursor\"");
        assertThat(body).isInstanceOf(List.class);
        assertThat((List<?>) body).hasSize(2);
    }

    @Test
    @DisplayName("🔴 老版本带 category / q（它一直会传）时，形态同样还是数组")
    void legacyClientWithFiltersStillGetsAnArray() {
        // 老版本一直在用品类筛选与搜索 —— 兼容分支不能只在「什么参数都不带」时成立。
        when(query.list(eq(ProductCategory.MAKANAN), eq("royal")))
                .thenReturn(List.of(view("tokA")));

        Object body = controller.list("MAKANAN", "royal", null, null).getBody();

        assertThat(json.writeValueAsString(body)).startsWith("[");
        verify(query, never()).page(any(), any(), any(), any());
    }

    @Test
    @DisplayName("🔴 空列表在老形态下是 `[]`，不是 `{\"items\":[]}`")
    void emptyLegacyResultIsAnEmptyArray() {
        when(query.list(isNull(), isNull())).thenReturn(List.of());

        assertThat(json.writeValueAsString(controller.list(null, null, null, null).getBody()))
                .isEqualTo("[]");
    }

    // ---------- 新版本：带分页参数 ----------

    @Test
    @DisplayName("带 cursor → 信封形态 {items, nextCursor, hasMore}")
    void cursorSwitchesToTheEnvelope() {
        when(query.page(isNull(), isNull(), eq("abc"), isNull()))
                .thenReturn(new ShopProductPageResponse(List.of(view("tokA")), "next", true));

        Object body = controller.list(null, null, "abc", null).getBody();

        verify(query, never()).list(any(), any());
        String wire = json.writeValueAsString(body);
        assertThat(wire).startsWith("{")
                .contains("\"items\"")
                .contains("\"nextCursor\":\"next\"")
                .contains("\"hasMore\":true");
    }

    @Test
    @DisplayName("只带 size（没有 cursor）→ 也是信封（请求第一页）")
    void sizeAloneAlsoSwitchesToTheEnvelope() {
        when(query.page(isNull(), isNull(), isNull(), eq(20)))
                .thenReturn(new ShopProductPageResponse(List.of(), null, false));

        controller.list(null, null, null, 20);

        verify(query).page(isNull(), isNull(), isNull(), eq(20));
        verify(query, never()).list(any(), any());
    }

    @Test
    @DisplayName("🔴 空白 cursor 也进分页形态 —— 它是「请求第一页」，不是「没传」")
    void blankCursorStillMeansPaged() {
        when(query.page(isNull(), isNull(), eq(""), isNull()))
                .thenReturn(new ShopProductPageResponse(List.of(), null, false));

        controller.list(null, null, "", null);

        // 新版本 App 第一页发的就是空游标；把它当成「没传」会让它收到一个数组而解析失败
        // —— 与老版本收到信封是同一个错误的两个方向。
        verify(query).page(isNull(), isNull(), eq(""), isNull());
        verify(query, never()).list(any(), any());
    }

    @Test
    @DisplayName("末页信封里 nextCursor 整键省略（NON_NULL），不是 null 占位")
    void lastPageOmitsNextCursor() {
        when(query.page(any(), any(), anyString(), any()))
                .thenReturn(new ShopProductPageResponse(List.of(view("tokA")), null, false));

        String wire = json.writeValueAsString(controller.list(null, null, "abc", null).getBody());

        assertThat(wire).doesNotContain("nextCursor").contains("\"hasMore\":false");
    }

    // ---------- 既有语义不因分页而变 ----------

    @Test
    @DisplayName("🔴 非法 category 仍 422 —— 两种形态下都拦在解析那一步")
    void invalidCategoryStill422InBothShapes() {
        // 解析在分派**之前**，所以不管走哪条路都一样拦住。
        // 静默忽略会让前端拿到「全部商品」却以为筛过了。
        assertThatThrownBy(() -> controller.list("NOT_A_CATEGORY", null, null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("品类");
        assertThatThrownBy(() -> controller.list("NOT_A_CATEGORY", null, "abc", null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("品类");

        verify(query, never()).list(any(), any());
        verify(query, never()).page(any(), any(), any(), any());
    }
}
