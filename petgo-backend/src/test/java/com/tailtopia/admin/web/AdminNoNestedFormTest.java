package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：后台模板里<b>不允许嵌套 {@code <form>}</b>（V1.3.0 Story 10.6 落地时加）。
 *
 * <h2>为什么这条值得一道守门</h2>
 * HTML 解析器遇到嵌套的 {@code <form>} 时<b>直接把内层丢掉</b>，不报错、不警告。
 * 页面渲染出来一切正常：按钮都在、输入框都在、样式也对。真按下去时，内层那些输入
 * 连同按钮一起属于<b>外层</b>那个 form —— 提交到外层的 action、带上外层的全部字段。
 *
 * <p>在本项目里这不是理论风险：模板 D 的 {@code tpl-d-config-card :: configCard}
 * 片段<b>自身就是一个 {@code <form>}</b>，而运营页上「一卡多行、每行一个写端点」
 * 的形状（D1 服务范围的区域表就是）一不留神就会把行级 form 塞进卡里。
 * 那时「停用某个区域」会被提交成「保存整张卡」，运营看到的是「点了停用，区域没停，
 * 但运费莫名其妙变了」—— 而服务端日志上是一次完全正常的 upsert。
 *
 * <p>纯文件扫描，无 Spring / 无 DB。
 */
class AdminNoNestedFormTest {

    private static final Path ROOT = Path.of("src", "main", "resources", "templates", "admin");
    /** {@code <form} 与 {@code </form}，按出现顺序。 */
    private static final Pattern FORM_TAG = Pattern.compile("</?form\\b", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("🔴 没有任何 admin 模板在一个 <form> 里再开一个 <form>")
    void noAdminTemplateNestsAForm() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                int depth = nestingDepth(strippedOf(Files.readString(p, StandardCharsets.UTF_8)));
                if (depth > 1) {
                    offenders.add(ROOT.relativize(p) + "（嵌套深度 " + depth + "）");
                }
            }
        }
        assertThat(offenders)
                .as("这些模板里有嵌套的 <form>。浏览器会静默丢掉内层：它的输入与按钮全部改属外层，"
                        + "点下去提交到外层的 action —— 页面上看不出任何异常，服务端日志也干净")
                .isEmpty();
    }

    /** 剥掉 HTML 注释与 Thymeleaf 解析级注释，免得注释里写的 {@code <form>} 被算进来。 */
    private static String strippedOf(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    /** 返回最大嵌套深度（1 = 有 form 但都是平级；>1 = 有嵌套）。 */
    private static int nestingDepth(String html) {
        Matcher m = FORM_TAG.matcher(html);
        int depth = 0;
        int max = 0;
        while (m.find()) {
            if (m.group().startsWith("</")) {
                depth = Math.max(0, depth - 1);
            } else {
                depth++;
                max = Math.max(max, depth);
            }
        }
        return max;
    }

    /**
     * 🔴 D1 的区域卡<b>刻意不套</b> {@code configCard}（Story 10.6 AC1 的落地形状）。
     *
     * <p>套进去就是上面那条守门要防的嵌套：{@code configCard} 自身是 form，
     * 而区域是「一行一次 upsert（{@code zones}）」+「启停另走一个端点（{@code zones/toggle}）」，
     * 两者都得是自己的 form。这条断言把那个决定钉住 —— 否则下一个人看到「卡②卡③都套了」
     * 会顺手把卡①也套上，而页面照常渲染。
     */
    @Test
    @DisplayName("🔴 D1 区域卡不套 configCard（套了就是 form 嵌套），但卡②卡③套")
    void theShippingZonesCardDeliberatelyDoesNotUseTheConfigCardShell() throws IOException {
        String html = strippedOf(Files.readString(ROOT.resolve("shop-shipping.html"),
                StandardCharsets.UTF_8));
        int zonesFrom = html.indexOf("th:fragment=\"zonesCard\"");
        assertThat(zonesFrom).as("找不到 zonesCard —— 片段改名了，这条断言此刻毫无意义").isGreaterThan(0);
        int zonesTo = html.indexOf("th:fragment=\"thresholdCard\"");
        assertThat(zonesTo).isGreaterThan(zonesFrom);

        assertThat(html.substring(zonesFrom, zonesTo))
                .as("区域卡套上了 configCard —— 那个片段本身就是 <form>，"
                        + "行级的 upsert 与启停两个 form 会被浏览器静默丢掉，"
                        + "「停用」会被提交成一次 upsert")
                .doesNotContain("tpl-d-config-card");
        // 反过来：卡②卡③是标准单 form 配置卡，必须走模板 D 的壳（否则「未改禁用 / 已修改」都没有）
        assertThat(html.substring(zonesTo))
                .as("免运门槛与退货地址两张卡应当套模板 D 的壳")
                .contains("tpl-d-config-card");
    }
}
