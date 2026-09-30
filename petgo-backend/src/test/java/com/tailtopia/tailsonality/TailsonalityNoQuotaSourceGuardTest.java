package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 2.1 · AC7.1 / AC8 · L0 源码守卫。
 *
 * <ul>
 *   <li>重测不留状态位（PRD §3.2）：tailsonality 包内不出现 {@code quota} / {@code remaining} / {@code retakeCount} 标识符；</li>
 *   <li>合规：包内与本 story 的迁移文件不出现四字母商标词（不区分大小写；Story 2.2 另有全局扫描）。</li>
 * </ul>
 */
class TailsonalityNoQuotaSourceGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/tailtopia/tailsonality");
    // 商标词拆开拼，避免本文件自己命中全局扫描。
    private static final Pattern TRADEMARK = Pattern.compile("m" + "bti", Pattern.CASE_INSENSITIVE);

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noQuotaOrRetakeCounterIdentifiers() throws IOException {
        Pattern banned = Pattern.compile("\\b(quota|remaining|retakeCount)\\b", Pattern.CASE_INSENSITIVE);
        for (Path p : sources()) {
            assertThat(banned.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()).as(p.toString()).isFalse();
        }
    }

    @Test
    void noTrademarkWordInPackageOrMigration() throws IOException {
        for (Path p : sources()) {
            assertThat(TRADEMARK.matcher(p.toString() + Files.readString(p, StandardCharsets.UTF_8)).find())
                    .as(p.toString()).isFalse();
        }
        try (Stream<Path> s = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path p : s.filter(x -> x.getFileName().toString().contains("tailsonality")).toList()) {
                assertThat(TRADEMARK.matcher(p.getFileName() + Files.readString(p, StandardCharsets.UTF_8)).find())
                        .as(p.toString()).isFalse();
            }
        }
    }
}
