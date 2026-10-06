package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 2.2 · AC4.2 · L0 合规扫描（后端侧；App 侧见 {@code test/tailsonality/trademark_scan_test.dart}）。
 *
 * <p>{@code src/main/java/**} 与 {@code src/main/resources/**} 的文本文件内容与<b>全部</b>文件名，不出现四字母商标词
 * （不区分大小写，<b>含注释</b>）——覆盖 API 路径、DTO 字段、迁移、埋点白名单。注释里需要提及时写「四字母商标词」。
 */
class TrademarkScanTest {

    // 拆开拼，避免测试源码本身成为命中源（本文件不在扫描范围，但全局 grep 时省心）。
    private static final Pattern TRADEMARK = Pattern.compile("m" + "bti", Pattern.CASE_INSENSITIVE);

    private static final Set<String> TEXT_EXT = Set.of(
            "java", "sql", "yml", "yaml", "properties", "html", "json", "xml", "js", "css", "txt");

    private static List<Path> walk(String root) throws IOException {
        try (Stream<Path> s = Files.walk(Path.of(root))) {
            return s.toList();
        }
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase();
    }

    @Test
    void mainSourcesAndResourcesNeverMentionTheTrademark() throws IOException {
        int scanned = 0;
        for (String root : List.of("src/main/java", "src/main/resources")) {
            for (Path p : walk(root)) {
                assertThat(TRADEMARK.matcher(p.toString()).find()).as("文件名：%s", p).isFalse();
                if (Files.isRegularFile(p) && TEXT_EXT.contains(ext(p))) {
                    String content;
                    try {
                        content = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (MalformedInputException e) {
                        content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    }
                    assertThat(TRADEMARK.matcher(content).find()).as("内容：%s", p).isFalse();
                    scanned++;
                }
            }
        }
        assertThat(scanned).isGreaterThan(100);
    }
}
