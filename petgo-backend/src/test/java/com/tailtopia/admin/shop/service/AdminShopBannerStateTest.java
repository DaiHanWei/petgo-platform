package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.domain.ShopBanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * L0：B17 Banner 的**三档状态**（V1.3.0 Story 10.3 · AC3）。
 *
 * <h2>为什么三档而不是两档</h2>
 * App <b>同一时间只展示一张</b>（已上架 + 权重最高）。只显示「启用 / 停用」的话，
 * 「已上架但被更高权重压住」的那些和「正在展示」的长得一模一样 —— 运营会反复怀疑
 * 「我明明上架了为什么没显示」，而正确的处置（调权重 or 把压住它的那张下架）根本无从下手。
 *
 * <p>纯逻辑 + 文件扫描，无 Spring / 无 DB。
 */
class AdminShopBannerStateTest {

    private static ShopBanner banner(long id, boolean active) {
        ShopBanner b = ShopBanner.create("k" + id, 1200, 400, 0);
        // id 由 JPA 生成，测试里用反射塞一个 —— stateOf 的判据就是「id 等不等于 liveId」
        try {
            var f = ShopBanner.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(b, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        if (active) {
            b.activate();
        }
        return b;
    }

    /**
     * V1.4.2 shop-admin Story 3.1 · AD-7：三档升级为六档（多张轮播）。原「生效中 / 被压住 / 未上架」对应
     * LIVE / OVER_LIMIT / INACTIVE；判定改走 {@code ShopBannerQueryService.states}（与 App 取数共用生效判定）。
     */
    @Test
    void theLiveOneTheOutrankedOneAndTheUnlistedOneAreThreeDifferentStates() {
        var states = com.tailtopia.shop.service.ShopBannerQueryService.states(
                java.util.List.of(banner(1L, true), banner(2L, true), banner(3L, false)), java.time.Instant.now(),
                java.util.Set.of(), java.util.Set.of(), k -> "https://cdn/" + k, 1);
        assertThat(states.get(1L)).isEqualTo(com.tailtopia.shop.service.ShopBannerQueryService.BannerState.LIVE);
        assertThat(states.get(2L)).as("有效但排在上限之后 —— 与「未上架」必须分得开")
                .isEqualTo(com.tailtopia.shop.service.ShopBannerQueryService.BannerState.OVER_LIMIT);
        assertThat(states.get(3L)).isEqualTo(com.tailtopia.shop.service.ShopBannerQueryService.BannerState.INACTIVE);
    }

    /** 一张已上架的都没有时，没有任何一张是「生效中」。 */
    @Test
    void withNoLiveBannerNothingIsMarkedLive() {
        var states = com.tailtopia.shop.service.ShopBannerQueryService.states(
                java.util.List.of(banner(1L, false)), java.time.Instant.now(), java.util.Set.of(), java.util.Set.of(),
                k -> "https://cdn/" + k, 6);
        assertThat(states.values()).doesNotContain(com.tailtopia.shop.service.ShopBannerQueryService.BannerState.LIVE);
    }

    /**
     * 🔴 <b>「生效中」必须问 App 端那条取图查询本身</b>（AC3「与 App 端取图规则同源」）。
     *
     * <h2>为什么钉的是「调了哪个方法」而不是「算出来对不对」</h2>
     * 后台完全可以自己写一遍「在全量降序列表里取第一条 active」——今天结果与 App 完全一致，
     * <b>任何比对结果的测试都是绿的</b>。问题在以后：App 侧的取图规则一旦变了
     * （加生效时间窗、加投放人群、加 A/B 分流），后台这份副本不会跟着变，
     * 而界面上<b>完全看不出来</b> —— 运营以为 A 在投，用户看到的是 B，
     * 且两边都「没有报错」。所以判据是引用关系本身。
     */
    @Test
    void theLiveIdComesFromTheSameQueryTheAppUses() throws IOException {
        String raw = Files.readString(Path.of("src", "main", "java", "com", "tailtopia",
                "admin", "shop", "service", "AdminShopBannerService.java"), StandardCharsets.UTF_8);
        // 🔴 **必须剥注释再判**：那个方法名在 liveId() 的 javadoc 正文里就写着一遍。
        //    不剥的话，把实现换成后台自己挑 active、注释原样留着 —— 断言照绿（命中的是注释）。
        //    Story 10.3 复审实测过这条：正反两个断言会一起失效。
        String code = raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");

        // 🔴 判据必须**限定在 liveId() 的方法体里**，不能扫整个类：
        //    `isActive()` 在 stateOf() 与 delete() 里各有合法的一处，扫全类的反向断言从落地起就是红的；
        //    而扫全类的正向断言又会被别处（含 javadoc）的同名字符串喂饱。两头都不成立。
        int from = code.indexOf("public Long liveId()");
        assertThat(from).as("找不到 liveId() —— 方法改名了，这条断言此刻毫无意义").isGreaterThan(0);
        String body = code.substring(from, code.indexOf("\n    }", from));

        // v1.4.2 shop Story 6.1：App 端取用规则单一来源改为 ShopBannerQueryService.liveBanners（排期 + 目标有效）
        assertThat(body)
                .as("后台的「生效中」判定没有走 App 端那个取用方法 —— "
                        + "自己再算一遍就是第二份判据，App 改了取图规则后两边会静默分叉")
                .contains("liveQuery.liveBanners(");
        assertThat(body).as("不得再用不看排期的旧查询")
                .doesNotContain("findFirstByActiveTrueOrderBySortWeightDescIdDesc");
        // 反向：liveId() 里不该出现「把全量列表拉回来自己挑」的任何形态。
        assertThat(body)
                .as("liveId() 又在自己挑 active 了 —— 这正是上面那条要防的第二份判据")
                .doesNotContain("isActive").doesNotContain("all()").doesNotContain("filter");
    }

    /** v1.4.2 shop Story 6.1 AC7.3：liveId = liveBanners 第一张；liveIds = 全部生效张（按取用顺序）。 */
    @Test
    void liveIdIsTheFirstOfLiveBannersAndLiveIdsAreAll() {
        var query = org.mockito.Mockito.mock(com.tailtopia.shop.service.ShopBannerQueryService.class);
        var service = new AdminShopBannerService(
                org.mockito.Mockito.mock(com.tailtopia.shop.repository.ShopBannerRepository.class),
                org.mockito.Mockito.mock(com.tailtopia.admin.audit.service.AdminAuditService.class),
                org.mockito.Mockito.mock(com.tailtopia.shop.repository.ShopProductRepository.class),
                org.mockito.Mockito.mock(com.tailtopia.shop.coupon.repository.ShopCouponRepository.class), query);
        org.mockito.Mockito.when(query.liveBanners(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(
                new com.tailtopia.shop.service.ShopBannerQueryService.LiveBanner(banner(7L, true), "u", "NONE", null),
                new com.tailtopia.shop.service.ShopBannerQueryService.LiveBanner(banner(3L, true), "u", "NONE", null)));
        assertThat(service.liveId()).isEqualTo(7L);
        assertThat(service.liveIds()).containsExactly(7L, 3L);

        org.mockito.Mockito.when(query.liveBanners(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
        assertThat(service.liveId()).isNull();
        assertThat(service.liveIds()).isEmpty();
    }
}
