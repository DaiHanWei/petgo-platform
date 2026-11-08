package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
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
 * L0：v1.4.2 shop · Story 2.3 · 迁移 ⑪ 文本守卫（读文件去注释，不连库；照 {@link PetNotifyMigrationTest}）。
 *
 * <ul>
 *   <li>通知类型 = 上一次重建（{@code V20261104_2010}）全集 + 电商两值（集合运算，不写数字）；</li>
 *   <li>通知类型 = {@link NotificationType} 枚举全集；</li>
 *   <li>DROP + ADD；通知类型约束是文件最后一个 {@code ADD CONSTRAINT}；不碰推送分类约束。</li>
 * </ul>
 */
class ShopNotifyMigrationTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path REBUILD = DIR.resolve("V20261105_1620__rebuild_ck_notifications_type_shop_coupon_reorder.sql");
    private static final Path PREVIOUS = DIR.resolve("V20261104_2010__rebuild_ck_notifications_type_and_push_mutes.sql");

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

    private static Set<String> types(Path file) throws IOException {
        Matcher m = Pattern.compile("ADD CONSTRAINT ck_notifications_type CHECK \\(type IN \\(([^)]*)\\)\\)")
                .matcher(ddl(file));
        assertThat(m.find()).as(file.getFileName() + " 的 ADD CONSTRAINT ck_notifications_type").isTrue();
        return quoted(m.group(1));
    }

    @Test
    void typesArePreviousRebuildPlusShopTwo() throws IOException {
        Set<String> expected = new LinkedHashSet<>(types(PREVIOUS));
        expected.add("COUPON_EXPIRING");
        expected.add("SHOP_ORDER_REORDERED");
        assertThat(types(REBUILD)).isEqualTo(expected);
    }

    @Test
    void typesEqualEnum() throws IOException {
        assertThat(types(REBUILD)).isEqualTo(Arrays.stream(NotificationType.values()).map(Enum::name)
                .collect(Collectors.toSet()));
    }

    @Test
    void dropThenAddAndTypeConstraintIsLastAndPushMutesUntouched() throws IOException {
        String raw = Files.readString(REBUILD, StandardCharsets.UTF_8);
        String d = ddl(REBUILD);
        assertThat(d).contains("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS ck_notifications_type;")
                .contains("ADD CONSTRAINT ck_notifications_type CHECK (type IN (");
        assertThat(d.indexOf("DROP CONSTRAINT IF EXISTS ck_notifications_type"))
                .isLessThan(d.indexOf("ADD CONSTRAINT ck_notifications_type"));
        assertThat(d.lastIndexOf("ADD CONSTRAINT")).isEqualTo(d.indexOf("ADD CONSTRAINT ck_notifications_type"));
        assertThat(raw).doesNotContain("ck_user_push_mutes_category");
        // 从 ADD 扫到文件尾（含注释）只有通知类型值（MentionNotifyTest 的扫描口径）
        int from = raw.indexOf("ADD CONSTRAINT ck_notifications_type CHECK");
        assertThat(quoted(raw.substring(from))).isEqualTo(types(REBUILD));
    }
}
