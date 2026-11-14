package com.tailtopia.entitlement;

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
 * L0：Story 4-1 AC6 —— 权益次数两张表的写语句只出现在 {@code com.tailtopia.entitlement} 包内
 * （照 v1.4.2 {@code CouponLedgerSingleEntryGuardTest}）。
 */
class EntitlementWriteGuardTest {

    private static final Path SRC = Path.of("src/main/java");
    private static final Path OWN = SRC.resolve("com/tailtopia/entitlement");
    private static final Pattern WRITE_SQL = Pattern.compile(
            "(?is)\\b(insert\\s+into|update|delete\\s+from)\\s+entitlement_credit(s|_uses)\\b");

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }

    @Test
    void writeSqlOnlyInEntitlementPackage() throws IOException {
        List<String> offenders;
        try (Stream<Path> s = Files.walk(SRC)) {
            offenders = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(OWN))
                    .filter(p -> WRITE_SQL.matcher(read(p)).find())
                    .map(Path::toString).toList();
        }
        assertThat(offenders).as("entitlement_credits / entitlement_credit_uses 只能由 entitlement 包写").isEmpty();
        // 自检：包内确有写语句，防止正则失效空转
        String dao = read(OWN.resolve("repository/EntitlementCreditDao.java"));
        assertThat(Pattern.compile("(?i)INSERT\\s+INTO\\s+entitlement_credits\\b").matcher(dao).find()).isTrue();
        assertThat(Pattern.compile("(?i)UPDATE\\s+entitlement_credits\\b").matcher(dao).find()).isTrue();
        assertThat(Pattern.compile("(?i)INSERT\\s+INTO\\s+entitlement_credit_uses\\b").matcher(dao).find()).isTrue();
    }
}
