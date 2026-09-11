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

    @Test
    void theLiveOneTheOutrankedOneAndTheUnlistedOneAreThreeDifferentStates() {
        ShopBanner live = banner(1L, true);
        ShopBanner outranked = banner(2L, true);
        ShopBanner unlisted = banner(3L, false);

        assertThat(AdminShopBannerService.stateOf(live, 1L)).isEqualTo("live");
        assertThat(AdminShopBannerService.stateOf(outranked, 1L))
                .as("已上架但不是被取到的那一张 —— 与「未上架」必须分得开，处置动作完全不同")
                .isEqualTo("activeNotLive");
        assertThat(AdminShopBannerService.stateOf(unlisted, 1L)).isEqualTo("inactive");
    }

    /** 一张已上架的都没有时，{@code liveId} 为 null —— 已上架的那些不能因此被标成「生效中」。 */
    @Test
    void withNoLiveBannerNothingIsMarkedLive() {
        assertThat(AdminShopBannerService.stateOf(banner(1L, false), null)).isEqualTo("inactive");
        assertThat(AdminShopBannerService.stateOf(banner(2L, true), null))
                .as("liveId 为 null 时，已上架的也只能是「被压住」而不是「生效中」")
                .isEqualTo("activeNotLive");
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

        assertThat(body)
                .as("后台的「生效中」判定没有走 App 端那条 repository 方法 —— "
                        + "自己再算一遍就是第二份判据，App 改了取图规则后两边会静默分叉")
                .contains("findFirstByActiveTrueOrderBySortWeightDescIdDesc");
        // 反向：liveId() 里不该出现「把全量列表拉回来自己挑」的任何形态。
        assertThat(body)
                .as("liveId() 又在自己挑 active 了 —— 这正是上面那条要防的第二份判据")
                .doesNotContain("isActive").doesNotContain("all()").doesNotContain("filter");
    }
}
