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
import org.junit.jupiter.api.Test;

/**
 * L0：Thymeleaf 链接表达式的语法护栏 —— 参数括号后面不许再接东西。
 *
 * <h2>守的是一个真实事故（Bug 20260908-486，2026-09-11 修）</h2>
 * 后台内容详情页的「展开全部 N 条回复」写成了
 * {@code @{'/admin/content/' + ${id}(commentPage=..,expand=..) + '#comment-' + ${id}}} ——
 * <b>参数块后面又接了 {@code + '#comment-'}</b>。链接表达式的参数括号必须是整个
 * {@code @{...}} 的结尾，后面再接内容就整体解析失败，该元素一渲染就抛异常。
 *
 * <p>🔴 <b>为什么没被现有测试网住</b>：那个元素挂着 {@code th:if="回复数 > 已显示数"}，
 * 只有「本页存在一条回复超过 3 条的评论」时才会渲染。渲染冒烟用的帖子没有这种评论，
 * 于是一路绿灯；运营翻到有这种评论的那一页才 500 —— 提报现象正是「第一页好好的，
 * 点了查看更多评论就报错」。<b>数据形态决定是否触发的模板 bug，冒烟测试天然抓不到。</b>
 *
 * <p>因此这条不验渲染、只验写法：纯文本扫描全部模板，任何 {@code @{...}} 里
 * 参数块之后还有内容的，一律判红。想拼锚点就用字面量替换
 * {@code |@{/path(params)}#fragment|} —— 那样 {@code @{...}} 自身是完整闭合的。
 *
 * <p>纯文件扫描，无 Spring / 无 DB。
 */
class AdminLinkExpressionSyntaxTest {

    /** 匹配一个 @{...}（允许内层一层 {}），内容记入组 1。 */
    private static final Pattern LINK = Pattern.compile("@\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}");

    /** HTML 注释：注释里举反例是正当的，不该被自己这条测试判红。 */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * 内层表达式与字符串字面量 —— 扫描前先遮掉。
     *
     * <p>🔴 不遮掉就会把 {@code ${d.post().id()}} 里方法调用的那个 {@code )} 当成参数块的收尾，
     * 于是满屏 {@code @{'/admin/users/' + ${u.id()}}} 这类完全正常的写法全被判红。
     */
    private static final Pattern INNER = Pattern.compile("[$#*]\\{[^{}]*\\}|'[^']*'");

    @Test
    void noContentAfterTheParameterBlockOfALinkExpression() throws IOException {
        Path root = Path.of("src/main/resources/templates");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                String html = COMMENT.matcher(Files.readString(f, StandardCharsets.UTF_8))
                        .replaceAll("");
                Matcher m = LINK.matcher(html);
                while (m.find()) {
                    String body = m.group(1);
                    // 遮掉内层表达式/字面量后剩下的才是链接表达式自己的结构。
                    String masked = INNER.matcher(body).replaceAll(match -> "#".repeat(
                            match.group().length()));
                    int close = masked.lastIndexOf(')');
                    if (close >= 0 && !masked.substring(close + 1).isBlank()) {
                        offenders.add(f + " → @{" + body + "}");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("链接表达式的参数括号必须是 @{...} 的结尾；要拼锚点请用 |@{/path(params)}#frag| 字面量替换")
                .isEmpty();
    }
}
