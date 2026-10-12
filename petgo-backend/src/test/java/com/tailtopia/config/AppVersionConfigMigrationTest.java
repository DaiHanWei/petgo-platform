package com.tailtopia.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V1.3.4 Story 3.1 · AC1.2（L0）：{@code app_version_config} 迁移的形态与预置值断言（纯文件读取）。
 *
 * <p>迁移本身要 DB 才跑得动（L1），但「预置值 = 决策日志 D-10 / D-18」与「发版检查项注释」靠读文件就能钉住。
 */
class AppVersionConfigMigrationTest {

    private static final Path MIGRATION = Path.of("src", "main", "resources", "db", "migration",
            "V20261012_1104__create_app_version_config.sql");

    private static String sql() {
        try {
            return Files.readString(MIGRATION, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不到迁移 " + MIGRATION.toAbsolutePath(), e);
        }
    }

    @Test
    void migrationFileExists() {
        assertThat(Files.isRegularFile(MIGRATION)).as("预分配迁移号 V20261012_1104，不得另取").isTrue();
    }

    /** D-18：发版检查项注释逐字存在。 */
    @Test
    void containsReleaseChecklistComment() {
        assertThat(sql()).contains(
                "发版检查项：上线前按商店实际版本核对；本迁移提交即冻结，改值走后台 AB-22 或另起 UPDATE 迁移，不得改本文件");
    }

    @Test
    void singletonCheckConstraint() {
        assertThat(sql()).contains("CHECK (id = 1)");
    }

    /** D-10 / D-18：两端最新 1.3.3、最低 1.0.0、两条标准商店链接；最新版本不得是 1.0.0。 */
    @Test
    void seedValuesMatchDecisions() {
        String sql = sql();
        assertThat(sql)
                .contains("'https://apps.apple.com/app/id6785361196'")
                .contains("'https://play.google.com/store/apps/details?id=com.tailtopia.app'");

        Matcher m = Pattern.compile(
                "VALUES\\s*\\(\\s*1\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'").matcher(sql);
        assertThat(m.find()).as("应有 id=1 的种子 INSERT").isTrue();
        String ios = m.group(1);
        String android = m.group(2);
        String min = m.group(3);
        assertThat(ios).isEqualTo("1.3.3").isNotEqualTo("1.0.0");
        assertThat(android).isEqualTo("1.3.3").isNotEqualTo("1.0.0");
        assertThat(min).isEqualTo("1.0.0");
    }
}
