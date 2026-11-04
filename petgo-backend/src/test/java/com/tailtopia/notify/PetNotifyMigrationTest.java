package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.preference.PushCategory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.4.2 social-topic · Story 1.2 · 迁移 ⑦ / ④ 文本守卫（读文件去注释，不连库）。
 *
 * <ul>
 *   <li>⑦ 两条约束都是 {@code DROP CONSTRAINT IF EXISTS} + {@code ADD CONSTRAINT}；推送分类 9 值、不含 ORDER_ACCOUNT；
 *       <b>推送分类约束在通知类型约束之前</b>（MentionNotifyTest 从通知类型 ADD 处扫到文件尾）；
 *       通知类型 = 上一次重建（V20261018_1400）全集 + 两值。</li>
 *   <li>④ 主键三列、日期索引、无 FK。</li>
 * </ul>
 */
class PetNotifyMigrationTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path REBUILD = DIR.resolve("V20261104_2010__rebuild_ck_notifications_type_and_push_mutes.sql");
    private static final Path PREVIOUS_TYPES = DIR.resolve("V20261018_1400__v140_rebuild_ck_notifications_type.sql");
    private static final Path LOG = DIR.resolve("V20261104_2020__create_pet_diary_notify_log.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static Set<String> quoted(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String segment(String d, String constraint) {
        Matcher m = Pattern.compile("ADD CONSTRAINT " + constraint + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(d);
        assertThat(m.find()).as(constraint + " 的 ADD CONSTRAINT").isTrue();
        return m.group(1);
    }

    @Test
    void bothConstraintsAreDropAndAddWithPushMutesFirst() throws IOException {
        String d = ddl(REBUILD);
        assertThat(d).contains("ALTER TABLE user_push_mutes DROP CONSTRAINT IF EXISTS ck_user_push_mutes_category;")
                .contains("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS ck_notifications_type;");
        int mutes = d.indexOf("ADD CONSTRAINT ck_user_push_mutes_category");
        int types = d.indexOf("ADD CONSTRAINT ck_notifications_type");
        assertThat(mutes).isPositive();
        assertThat(types).as("通知类型约束必须在推送分类约束之后（守卫从它扫到文件尾）").isGreaterThan(mutes);
        // 通知类型 ADD 之后到文件尾只有通知类型值
        assertThat(quoted(d.substring(types))).isEqualTo(quoted(segment(d, "ck_notifications_type")));
    }

    @Test
    void pushMutesCategoryIsNineLockableValues() throws IOException {
        Set<String> values = quoted(segment(ddl(REBUILD), "ck_user_push_mutes_category"));
        assertThat(values).hasSize(9).contains("PET_DIARY").doesNotContain("ORDER_ACCOUNT");
        assertThat(values).isEqualTo(Arrays.stream(PushCategory.values()).filter(PushCategory::lockable)
                .map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void notificationTypesArePreviousRebuildPlusTwo() throws IOException {
        Set<String> previous = quoted(segment(ddl(PREVIOUS_TYPES), "ck_notifications_type"));
        Set<String> now = quoted(segment(ddl(REBUILD), "ck_notifications_type"));
        Set<String> expected = new LinkedHashSet<>(previous);
        expected.add("PET_FOLLOWED");
        expected.add("PET_DIARY_POSTED");
        assertThat(now).isEqualTo(expected);
        assertThat(now).isEqualTo(Arrays.stream(NotificationType.values()).map(Enum::name)
                .collect(Collectors.toSet()));
    }

    @Test
    void notifyLogHasThreeColumnPrimaryKeyDateIndexAndNoForeignKey() throws IOException {
        String d = ddl(LOG);
        assertThat(d).contains("CREATE TABLE pet_diary_notify_log")
                .contains("CONSTRAINT pk_pet_diary_notify_log PRIMARY KEY (follower_id, pet_profile_id, wib_date)")
                .contains("wib_date DATE NOT NULL")
                .contains("CREATE INDEX idx_pet_diary_notify_log_date ON pet_diary_notify_log (wib_date)")
                .doesNotContain("REFERENCES");
    }
}
