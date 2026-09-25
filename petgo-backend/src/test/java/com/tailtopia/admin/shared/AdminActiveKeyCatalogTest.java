package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：每个后台 Controller 写进 model 的 {@code active}（侧栏高亮键）都必须是 {@link AdminPageCatalog} 里某个页面的
 * {@code activeKey}（bug 20260924-563）。
 *
 * <p>事故：批量内容页四个入口写的是 {@code "seed"}（种子内容发布的键），侧栏于是高亮「种子内容发布」而不是「批量内容」。
 * 键写错不会报错、页面照常渲染，只能靠这条静态扫描拦住。
 *
 * <p>扫源码文本（surefire 工作目录 = 模块根），纯文件读取，无 Spring / DB。
 */
class AdminActiveKeyCatalogTest {

    private static final Path ADMIN = Path.of("src", "main", "java", "com", "tailtopia", "admin");

    /** {@code addAttribute("active", <expr>)}：捕获第二个参数原文。 */
    private static final Pattern ACTIVE = Pattern.compile("addAttribute\\(\\s*\"active\"\\s*,\\s*([^)]*)\\)");

    private static final Pattern LITERAL = Pattern.compile("^\"([^\"]*)\"$");

    /** 不进侧栏的开发自检页，故意没有目录条目。 */
    private static final Set<String> EXEMPT = Set.of("_kitchen-sink");

    @Test
    void everyControllerActiveKeyExistsInPageCatalog() throws IOException {
        Set<String> catalogKeys = AdminPageCatalog.PAGES.stream()
                .map(AdminPageCatalog.Page::activeKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<String> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(ADMIN)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".java")).sorted().toList()) {
                Matcher m = ACTIVE.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    String arg = m.group(1).trim();
                    Matcher lit = LITERAL.matcher(arg);
                    if (!lit.matches()) {
                        offenders.add(p + " → 非字面量 active（无法静态校验，请改成字面量）：" + arg);
                        continue;
                    }
                    String key = lit.group(1);
                    scanned.add(key);
                    if (!EXEMPT.contains(key) && !catalogKeys.contains(key)) {
                        offenders.add(p + " → active=\"" + key + "\" 不在 AdminPageCatalog 的 activeKey 里");
                    }
                }
            }
        }
        assertThat(scanned).as("应扫到后台 Controller 的 active 设置").isNotEmpty();
        assertThat(offenders)
                .as("侧栏高亮键必须取自 AdminPageCatalog（否则侧栏高亮错项或不高亮）")
                .isEmpty();
    }

    /** 批量内容页的高亮键就是 seed-batches（563 的直接回归点）。 */
    @Test
    void seedBatchWorkspaceHighlightsBulkContentNotSeedPost() throws IOException {
        String src = Files.readString(ADMIN.resolve(Path.of("seed", "web", "AdminSeedBatchWorkspaceController.java")),
                StandardCharsets.UTF_8);
        assertThat(src).doesNotContain("addAttribute(\"active\", \"seed\")");
        assertThat(src).contains("addAttribute(\"active\", \"seed-batches\")");
    }
}
