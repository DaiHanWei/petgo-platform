package com.tailtopia.admin.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 架构守护（bug 20260731-440）：权限常量表与 {@code @PreAuthorize}/{@code sec:authorize} 双轨一致性。
 *
 * <p>历史缺陷根因：常量类单点定义 + 各 Controller 手写表达式，两轨间无任何检查——新页面漏写门控、
 * 写了对不上的码（勾选页出现「勾了也没用」的死码）都静默通过。本测试扫源码文件强制两个方向：
 * ① 代码/模板中每个 {@code hasAuthority('x')} 的 x 必须在 {@link AdminPermissions#ALL}（防拼错/幽灵码）；
 * ② ALL 中每个码必须至少被一处 {@code hasAuthority} 引用（防勾选页死码复发）。
 *
 * <p>源码扫描以 surefire 工作目录（模块根）定位 {@code src/main}；纯文件读取，无 Spring / DB。
 */
class AdminPermissionWiringTest {

    /** 只匹配真实码型 {@code <模块>.<动作>}（全小写点分）；javadoc 占位示例（如 {@code '<code>'}）不计。 */
    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\('([a-z_]+\\.[a-z_]+)'\\)");

    @Test
    void everyReferencedAuthorityIsRegisteredAndEveryCodeIsWired() throws IOException {
        Path main = Path.of("src", "main");
        assertThat(main).as("需在模块根运行（surefire 默认）").isDirectory();

        Set<String> referenced = new HashSet<>();
        try (Stream<Path> files = Files.walk(main)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".java") || name.endsWith(".html");
            }).forEach(p -> {
                String text;
                try {
                    text = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                Matcher m = HAS_AUTHORITY.matcher(text);
                while (m.find()) {
                    referenced.add(m.group(1));
                }
            });
        }

        // ①：引用的码都在册（防拼错——拼错的码任何账号都授不出去，等于永久 403/永久裸奔）。
        assertThat(referenced)
                .as("源码中 hasAuthority 引用了不在 AdminPermissions.ALL 的码（拼错或忘登记）")
                .allSatisfy(code -> assertThat(AdminPermissions.ALL).contains(code));

        // ②：在册的码都有落点（防「勾了也没用」的死码回潮）。
        assertThat(AdminPermissions.ALL)
                .as("AdminPermissions.ALL 存在无任何 hasAuthority 落点的死码（要么接线要么摘除）")
                .allSatisfy(code -> assertThat(referenced).contains(code));
    }
}
