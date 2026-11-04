package com.tailtopia.notify.preference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.4.0 ④ Story 1.1 · AC1 迁移文本守卫（读文件，不连库）。
 *
 * <p>V1.4.2 social-topic Story 1.2 · AC3.3：CHECK 值改为取<b>版本序最后一条</b>定义 / 重建
 * {@code ck_user_push_mutes_category} 的迁移（同 {@code MentionNotifyTest.lastRebuildMigration} 的判据 ——
 * 该约束的改法是 DROP + ADD 重列全集，谁排在版本序最后谁说了算）；建表形状仍读 {@code V20261018_1800}。
 */
class UserPushMutesMigrationTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");

    private static final Path FILE = DIR.resolve("V20261018_1800__create_user_push_mutes.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        return ddl(FILE);
    }

    private static String ddl(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    /** 版本序最后一条定义 / 重建推送分类约束的迁移。 */
    static Path lastCategoryConstraintMigration() throws IOException {
        try (var files = Files.list(DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(UserPushMutesMigrationTest::definesCategoryConstraint)
                    .max(java.util.Comparator.comparing(UserPushMutesMigrationTest::migrationSortKey))
                    .orElseThrow(() -> new AssertionError("找不到任何定义 ck_user_push_mutes_category 的迁移"));
        }
    }

    private static boolean definesCategoryConstraint(Path p) {
        try {
            return ddl(p).contains("CONSTRAINT ck_user_push_mutes_category CHECK");
        } catch (IOException e) {
            return false;
        }
    }

    /** Flyway 版本序排序键（同 {@code MentionNotifyTest.migrationSortKey}：位数多的更大，位数相同按字典序）。 */
    private static String migrationSortKey(Path p) {
        String name = p.getFileName().toString();
        String version = name.substring(1, name.indexOf("__")).replace("_", "");
        return String.format("%03d%s", version.length(), version);
    }

    private static Set<String> checkValues(String d) {
        Matcher m = Pattern.compile("CONSTRAINT ck_user_push_mutes_category CHECK \\(category IN \\(([^)]*)\\)\\)")
                .matcher(d);
        assertThat(m.find()).as("ck_user_push_mutes_category 存在").isTrue();
        Set<String> values = new HashSet<>();
        Matcher q = Pattern.compile("'([A-Z_]+)'").matcher(m.group(1));
        while (q.find()) {
            values.add(q.group(1));
        }
        return values;
    }

    @Test
    void tableShape() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE TABLE user_push_mutes")
                .contains("PRIMARY KEY (user_id, category)")
                .contains("REFERENCES users (id) ON DELETE CASCADE")
                .contains("CREATE INDEX idx_user_push_mutes_category_user ON user_push_mutes (category, user_id)");
    }

    @Test
    void checkListsExactlyTheLockableCategories() throws IOException {
        Set<String> values = checkValues(ddl(lastCategoryConstraintMigration()));
        assertThat(values).doesNotContain("ORDER_ACCOUNT");
        // 防以后在 PushCategory 加可关分类却忘了重建 CHECK
        Set<String> lockable = Arrays.stream(PushCategory.values()).filter(PushCategory::lockable)
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(values).isEqualTo(lockable);
    }

    @Test
    void latestCategoryConstraintIsV142Rebuild() throws IOException {
        // V1.4.2 Story 1.2：重建后为 9 个可关分类（含 PET_DIARY）；以后再加可关分类时这里随之更新
        assertThat(lastCategoryConstraintMigration().getFileName().toString())
                .isEqualTo("V20261104_2010__rebuild_ck_notifications_type_and_push_mutes.sql");
        assertThat(checkValues(ddl(lastCategoryConstraintMigration()))).hasSize(9).contains("PET_DIARY");
    }
}
