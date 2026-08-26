package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：后台模板的**结构**护栏 —— 页面上该有的东西必须真的在会被渲染的范围内。
 *
 * <h2>这条守的是一个真实事故（2026-08-26 实机截图发现）</h2>
 * 「首页推荐算法」整块（Story 16.4 交付）写在了 {@code th:fragment="content"} 的
 * <b>闭合标签之外</b>。业务页经 {@code admin/layout :: page(~{::content})} 渲染，
 * 只取那一个片段 ⇒ <b>整块被静默丢弃，从交付起就没在页面上出现过</b>。
 * 连带 Story 17.1 加在同一表单里的「限流系数」也一起不可见 ——
 * 那两条 story 的「后台可配」实际上办不到，运营根本找不到那些参数。
 *
 * <p>⚠️ <b>当时全套测试都是绿的。</b>渲染冒烟只验「不报错」，服务层测试直接调 service，
 * 端点测试直接 POST —— <b>丢掉一整块 HTML 不会让任何一条变红。</b>
 *
 * <h2>为什么是这条，而不是逐页手写断言</h2>
 * 事故当天补的是逐块列举（「运营配置页必须有定价/PawCoin/分享奖励/档位」）。
 * 那种写法只守住被列举的那两页，<b>新加一页、新加一块都不会自动进网</b> ——
 * 同样的事故换个页面还会再来一次，而且下一次同样不会有人先想到回来补一行。
 *
 * <p>本条改成<b>遍历全部模板的结构规则</b>：任何 {@code <form>} / {@code <table>} /
 * {@code data-section} 都必须落在某个 {@code th:fragment} 之内。新模板一落地即受约束，
 * 不依赖谁记得。代价是它只能看结构、不能看运行期（渲染报错、漏译、状态分支）——
 * 那些仍归 {@code AdminPagesRenderSmokeTest} 那条 L1 链路。
 *
 * <p>纯文件扫描，无 Spring / 无 DB。
 */
class AdminTemplateStructureTest {

    private static final Path DIR = Path.of("src", "main", "resources", "templates", "admin");

    /**
     * 独立整页：自己就是完整 HTML、不经 layout 片段拼装，故不受本规则约束。
     *
     * <p>⚠️ 往这里加名字等于给一个页面开豁免 —— 只有「这一页真的不经 layout 渲染」才成立。
     * 「加进来就绿了」不是理由：那恰恰说明它的内容掉在片段外了。
     */
    private static final Set<String> STANDALONE_PAGES = Set.of("login.html");

    private static final Pattern OPEN_TAG = Pattern.compile("<([a-zA-Z][a-zA-Z0-9:._-]*)");

    /**
     * 「实质内容」的锚点。取表单 / 表格 / 显式区块标记三种：它们是运营真正要用的东西
     * （填写、查看、操作），掉在片段外就等于该功能不存在。
     * 不取纯文本与标题 —— 那类丢失顶多是说明缺一句，且会淹掉真正的信号。
     */
    private static final Pattern ANCHOR = Pattern.compile("(<form\\b)|(<table\\b)|(data-section=)");

    /** 一个 {@code th:fragment} 元素占据的行区间（含首尾）。 */
    private record Span(int start, int end) {
        boolean covers(int line) {
            return start <= line && line <= end;
        }
    }

    @Test
    void everyLayoutRenderedTemplateDeclaresAFragment() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Path f : templates()) {
            if (STANDALONE_PAGES.contains(fileName(f))) {
                continue;
            }
            if (!Files.readString(f, StandardCharsets.UTF_8).contains("th:fragment=")) {
                offenders.add(fileName(f));
            }
        }
        assertThat(offenders)
                .as("🔴 这些模板经 layout 渲染却没有声明 th:fragment —— 整页内容不会被输出")
                .isEmpty();
    }

    /**
     * 🔴 全部后台模板：表单 / 表格 / 区块标记都必须在某个 {@code th:fragment} 之内。
     *
     * <p>合法的例外只有一种：<b>专门包住片段的外壳</b>。如 {@code content.html} 里那个
     * {@code <table hidden>} 只是让 {@code <tr th:fragment="row">}（HTMX 原地换行用）
     * 成为合法 HTML，它本身不参与整页渲染。判据是「这个元素内部起了一个片段」，
     * 而不是名单豁免 —— 名单会被当成绿灯开关用。
     */
    @Test
    void noRealContentLivesOutsideAnyFragment() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : templates()) {
            if (STANDALONE_PAGES.contains(fileName(f))) {
                continue;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<Span> fragments = fragmentSpans(lines);
            for (int i = 0; i < lines.size(); i++) {
                final int line = i;
                Matcher m = ANCHOR.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                if (fragments.stream().anyMatch(s -> s.covers(line))) {
                    continue;
                }
                if (wrapsAFragment(lines, i, m, fragments)) {
                    continue;
                }
                offenders.add(fileName(f) + ":" + (i + 1) + "  " + lines.get(i).trim());
            }
        }
        assertThat(offenders)
                .as("🔴 这些内容在 th:fragment 之外 —— 页面渲染时会被静默丢弃，"
                        + "表现为「功能交付了但运营在界面上找不到」（2026-08-26 事故形态）")
                .isEmpty();
    }

    /** 该锚点元素内部起了一个片段 ⇒ 它是片段的外壳，不参与整页渲染，合法。 */
    private boolean wrapsAFragment(List<String> lines, int line, Matcher anchor, List<Span> fragments) {
        String tag = anchor.group(1) != null ? "form"
                : anchor.group(2) != null ? "table"
                        : ownerTag(lines, line, anchor.start(3));
        if (tag == null) {
            return false;
        }
        int end = closingLine(lines, line, tag);
        return fragments.stream().anyMatch(s -> line <= s.start() && s.start() <= end);
    }

    /** 全部 {@code th:fragment} 元素的行区间。片段可挂在任意标签上（div / tr / th:block…）。 */
    private List<Span> fragmentSpans(List<String> lines) {
        List<Span> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int col = lines.get(i).indexOf("th:fragment=");
            if (col < 0) {
                continue;
            }
            String tag = ownerTag(lines, i, col);
            if (tag != null) {
                out.add(new Span(i, closingLine(lines, i, tag)));
            }
        }
        return out;
    }

    /**
     * 携带该属性的开标签名：取属性位置之前最近的一个开标签；本行没有则往前找
     * （开标签常跨行写，属性落在第二行）。
     */
    private String ownerTag(List<String> lines, int line, int col) {
        String tag = lastOpenTag(lines.get(line).substring(0, Math.max(col, 0)));
        for (int j = line - 1; tag == null && j >= 0; j--) {
            tag = lastOpenTag(lines.get(j));
        }
        return tag;
    }

    private String lastOpenTag(String segment) {
        Matcher m = OPEN_TAG.matcher(segment);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** 同名标签深度配对求闭合行；找不到闭合（模板写坏）则退到文件末尾。 */
    private int closingLine(List<String> lines, int start, String tag) {
        Pattern open = Pattern.compile("<" + Pattern.quote(tag) + "\\b");
        Pattern close = Pattern.compile("</" + Pattern.quote(tag) + "\\s*>");
        int depth = 0;
        for (int j = start; j < lines.size(); j++) {
            depth += count(open, lines.get(j));
            depth -= count(close, lines.get(j));
            if (depth <= 0) {
                return j;
            }
        }
        return lines.size() - 1;
    }

    private int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private List<Path> templates() throws IOException {
        try (Stream<Path> files = Files.walk(DIR)) {
            return files.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    private String fileName(Path p) {
        return p.getFileName().toString();
    }
}
