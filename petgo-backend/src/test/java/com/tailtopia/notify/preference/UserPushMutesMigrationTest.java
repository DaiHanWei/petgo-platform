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
 */
class UserPushMutesMigrationTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261018_1800__create_user_push_mutes.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
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
        Set<String> values = checkValues(ddl());
        assertThat(values).hasSize(8).doesNotContain("ORDER_ACCOUNT");
        // 防以后在 PushCategory 加可关分类却忘了重建 CHECK
        Set<String> lockable = Arrays.stream(PushCategory.values()).filter(PushCategory::lockable)
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(values).isEqualTo(lockable);
    }
}
