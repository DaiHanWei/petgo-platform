package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * L0：B18 库存页的两条<b>判据同源</b>护栏（V1.3.0 Story 10.4 · AC1 / AC2）。纯文件扫描，无 Spring / 无 DB。
 */
class AdminShopInventoryGuardTest {

    private static final Path SERVICE = Path.of("src", "main", "java", "com", "tailtopia",
            "admin", "shop", "service", "AdminShopInventoryService.java");
    private static final Path DRAWER = Path.of("src", "main", "resources", "templates", "admin",
            "fragments", "drawer-shop-inventory.html");
    private static final List<String> PACKS = List.of("messages.properties",
            "messages_zh_CN.properties", "messages_en.properties", "messages_id.properties");

    /** 剥掉注释再判 —— 方法名 / 关键字在 javadoc 正文里往往就写着一遍（Story 10.3 实测踩过）。 */
    private static String codeOf(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * 🔴 <b>摘要条的「售罄 / 低库存」必须问状态列那同一个判定</b>（AC1）。
     *
     * <h2>为什么钉的是「调了哪个方法」而不是「算出来对不对」</h2>
     * 摘要条完全可以自己写一条 {@code count(*) FILTER (WHERE actual - locked <= 5)}——
     * 今天与状态列的结果一字不差，<b>任何比对结果的测试都是绿的</b>。问题在以后：
     * 低库存阈值是配置项（{@code petgo.shop.low-stock-threshold}），线上调一次，
     * 摘要条与状态列就会给出<b>互相矛盾的两个答案</b>，而界面上完全看不出来 ——
     * 「低库存 0」配着一整列黄色的「库存紧张」，运营只会以为摘要条坏了，不会想到阈值有两份。
     */
    @Test
    void theSummaryBucketsComeFromTheSameStatusRuleTheRowsUse() throws IOException {
        String code = codeOf(SERVICE);
        int from = code.indexOf("public Summary summary()");
        assertThat(from).as("找不到 summary() —— 方法改名了，这条断言此刻毫无意义").isGreaterThan(0);
        String body = code.substring(from, code.indexOf("\n    }", from));

        assertThat(body)
                .as("摘要条的分档没有走 InventoryService#statusOf —— 自己再算一遍就是第二份判据，"
                        + "阈值一改，摘要条与状态列会静默分叉")
                .contains("inventory.statusOf");
        // 反向：summary() 里不该出现自己比阈值的任何形态。
        assertThat(body)
                .as("summary() 里又出现了阈值字样 —— 这正是上面那条要防的第二份判据")
                .doesNotContain("Threshold").doesNotContain("threshold");
    }

    /**
     * 🔴 <b>盘点确认框复述的是「差异数」，不是盘点值</b>（AC2）。
     *
     * <h2>为什么差异数是必须的</h2>
     * 盘点填的是「我数出来多少」，而真正落账的是「差多少」。只复述盘点值的话，
     * 把 12 误填成 120 与正确填 120 在确认框里<b>长得一模一样</b> —— 运营没有任何机会发现自己多按了一个 0。
     * 而盘点是 {@code actual} 的直接赋值：一次错误盘点会同时制造超卖与成本失真，且只能再开一笔反向流水，
     * 原始那笔永远留在账上。差异 {@code +108} 才是会让人停下来的那个数。
     */
    @Test
    void theStocktakeConfirmationRestatesTheDifferenceNotJustTheCountedValue() throws IOException {
        for (String pack : PACKS) {
            Properties props = new Properties();
            try (var in = Files.newBufferedReader(
                    Path.of("src", "main", "resources", "i18n", pack), StandardCharsets.UTF_8)) {
                props.load(in);
            }
            String text = props.getProperty("admin.v130.shopInventory.confirmStocktake");
            assertThat(text).as(pack + " 缺少盘点确认文案").isNotNull();
            assertThat(text).as(pack + " 的盘点确认文案少了占位符：{0} 系统当前 / {1} 盘点值 / {2} 差异。"
                            + "少了 {2} 就退化成「只复述盘点值」，多按一个 0 看不出来")
                    .contains("{0}").contains("{1}").contains("{2}");
        }

        // 差异由抽屉里的脚本现算（提交那一刻的值），这里钉住它确实是 counted − actual。
        String drawer = Files.readString(DRAWER, StandardCharsets.UTF_8);
        assertThat(drawer)
                .as("抽屉脚本没有在算差异 —— 那么 {2} 会被填成空串，确认框反而比不复述还糟")
                .contains("counted - actual");
        assertThat(drawer)
                .as("差异没带正负号：+108 与 -108 是两件完全不同的事（多盘出来 vs 丢货）")
                .contains("(diff > 0 ? '+' : '')");
    }

    /**
     * 🔴 <b>四个操作的 hx-target 一律是抽屉体</b>（AC2）。
     *
     * <p>指着列表容器的话，「报损数量超过可售」（422）、「无 cost_edit 点了采购入库」（403）
     * 会把<b>整张表换成一行红字</b>，而那行字还被抽屉遮罩盖着：运营看到的是「点了没反应」，
     * 关掉抽屉才发现表格没了，只能 F5。Story 10.3 已经因为这个改过一轮。
     */
    @Test
    void everyDrawerFormPostsBackIntoTheDrawerBody() throws IOException {
        String drawer = Files.readString(DRAWER, StandardCharsets.UTF_8);
        int forms = drawer.split("hx-post=", -1).length - 1;
        assertThat(forms).as("抽屉里应有四个操作表单（采购入库 / 退货入库 / 报损 / 盘点）").isEqualTo(4);
        assertThat(drawer.split("hx-target=\"#shop-inventory-drawer-body\"", -1).length - 1)
                .as("有表单的 hx-target 不是抽屉体 —— 它的 4xx 会把整张库存表换成一行红字，"
                        + "而那行字被遮罩盖着")
                .isEqualTo(forms);
    }

    /**
     * 🔴 <b>抽屉体的 id 是页面 {@code res} 拼出来的，两边必须对得上</b>。
     *
     * <p>{@code tpl-b-list} 用 {@code th:with="res='…'"} 拼出 {@code <res>-drawer-body}，
     * 而抽屉里四个表单的 {@code hx-target} 是<b>手写</b>的同名 id。两边差一个字母时：
     * htmx 找不到目标就<b>什么都不做</b>（只在浏览器 console 里留一行），
     * 服务端一切正常、日志干净 —— 运营看到的是「点保存没反应」，而这在 L0 / 服务端测试里
     * 一条都照不出来。这条断言把两边钉在一起。
     */
    @Test
    void theDrawerTargetIdMatchesTheResTheListPageDeclares() throws IOException {
        String page = Files.readString(Path.of("src", "main", "resources", "templates", "admin",
                "shop-inventory.html"), StandardCharsets.UTF_8);
        assertThat(page)
                .as("库存页没有声明 res='shop-inventory' —— 抽屉 id 会退回 tpl-b-list 的默认 'item'，"
                        + "四个表单的 hx-target 就全部指向一个不存在的元素")
                .contains("res='shop-inventory'");

        String drawer = Files.readString(DRAWER, StandardCharsets.UTF_8);
        assertThat(drawer).contains("#shop-inventory-drawer-body");
    }
}
