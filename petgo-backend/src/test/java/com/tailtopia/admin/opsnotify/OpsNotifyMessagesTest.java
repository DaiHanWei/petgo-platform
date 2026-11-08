package com.tailtopia.admin.opsnotify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0：后台系统通知 AB-37 文案与新建页结构（V1.4.2 Story 7.2 · AC9 / AC1 / AC3）。纯文件扫描，无 Spring 上下文。
 *
 * <ul>
 *   <li>PRD §7 AB-37 表的 key（7.1 + 7.2）在四份 messages 里都存在且非空；</li>
 *   <li>含 {@code {0}} 的值里没有落单的 {@code '}（MessageFormat 会把它当转义吞字）；</li>
 *   <li>{@code estimate.hint} / {@code var.hint} 三语逐字等于 PRD §7 新句；</li>
 *   <li>新建页：4 个 contenteditable 编辑区 + 4 个同名隐藏字段 + 8 个变量按钮、不再有 maxlength；
 *       {@code data-error-steps} / {@code data-error-fields} 含 {@code var.typed}（7.1 复审：手打 422 要跳回 ① 并标红）。</li>
 * </ul>
 */
class OpsNotifyMessagesTest {

    private static final List<String> FILES = List.of("/i18n/messages_zh_CN.properties",
            "/i18n/messages_en.properties", "/i18n/messages_id.properties", "/i18n/messages.properties");

    private static final List<String> KEYS = List.of(
            // 7.2 写入
            "admin.opsNotify.var.btn.nickname", "admin.opsNotify.var.btn.petName", "admin.opsNotify.var.example",
            "admin.opsNotify.var.hint", "admin.opsNotify.review.excludedNoPet", "admin.opsNotify.estimate.hint",
            // 7.1 写入（核对仍在）
            "admin.opsNotify.var.tag.nickname", "admin.opsNotify.var.tag.petName", "admin.err.ops-notify.var.typed",
            "admin.err.ops-notify.text.too-long", "admin.err.ops-notify.audience-empty-no-pet",
            "admin.opsNotify.review.previewUser", "admin.opsNotify.review.previewUserValue",
            "admin.opsNotify.review.previewUserNone", "admin.opsNotify.review.shuffle",
            "admin.opsNotify.reach.skippedNoPet");

    /** PRD §7 AB-37 表原句（改线上值 / 新句）。 */
    private static final Map<String, Map<String, String>> VERBATIM = Map.of(
            "/i18n/messages_zh_CN.properties", Map.of(
                    "admin.opsNotify.estimate.hint",
                    "已自动排除：封号、已注销、虚拟号；用了「宠物昵称」时另排除没有宠物的用户。条件之间为「且」。",
                    "admin.opsNotify.var.hint",
                    "变量只能用输入框下方的按钮插入，每个变量按 20 字计入字数。没填昵称的用户显示「Sobat」（EN「there」）；"
                            + "用了「宠物昵称」时，没有宠物的用户不会收到。",
                    "admin.opsNotify.review.excludedNoPet", "已排除 {0} 位没有宠物的用户"),
            "/i18n/messages_en.properties", Map.of(
                    "admin.opsNotify.estimate.hint",
                    "Automatically excluded: banned, deleted and virtual accounts; if \"Pet name\" is used, users "
                            + "without a pet are excluded too. Conditions are combined with AND.",
                    "admin.opsNotify.var.hint",
                    "Variables can only be inserted with the buttons below each box, and each variable counts as 20 "
                            + "characters. Users without a nickname see \"Sobat\" (EN: \"there\"). If \"Pet name\" is "
                            + "used, users without a pet won't receive it.",
                    "admin.opsNotify.review.excludedNoPet", "{0} users without a pet excluded"),
            "/i18n/messages_id.properties", Map.of(
                    "admin.opsNotify.estimate.hint",
                    "Otomatis dikecualikan: akun diblokir, dihapus, dan virtual; jika memakai \"Nama hewan\", "
                            + "pengguna tanpa hewan juga dikecualikan. Antar syarat berlaku \"dan\".",
                    "admin.opsNotify.var.hint",
                    "Variabel hanya bisa disisipkan lewat tombol di bawah kotak isian, dan setiap variabel dihitung "
                            + "20 karakter. Pengguna tanpa nama panggilan melihat \"Sobat\" (EN: \"there\"). Jika memakai "
                            + "\"Nama hewan\", pengguna tanpa hewan tidak akan menerimanya.",
                    "admin.opsNotify.review.excludedNoPet", "{0} pengguna tanpa hewan dikecualikan"));

    private static Properties load(String f) throws IOException {
        Properties p = new Properties();
        try (InputStream in = OpsNotifyMessagesTest.class.getResourceAsStream(f)) {
            assertThat(in).as(f).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OpsNotifyMessagesTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ab37KeysPresentInEveryBundle() throws Exception {
        for (String f : FILES) {
            Properties p = load(f);
            for (String k : KEYS) {
                assertThat(p.getProperty(k)).as(f + " " + k).isNotBlank();
            }
        }
    }

    @Test
    void placeholderValuesHaveNoLoneApostrophe() throws Exception {
        for (String f : FILES) {
            Properties p = load(f);
            for (String k : KEYS) {
                String v = p.getProperty(k);
                if (v != null && v.contains("{0}")) {
                    assertThat(v.replace("''", "")).as(f + " " + k + " 含 {0}，撇号须写 ''").doesNotContain("'");
                }
            }
        }
    }

    @Test
    void prdSentencesVerbatim() throws Exception {
        for (Map.Entry<String, Map<String, String>> e : VERBATIM.entrySet()) {
            Properties p = load(e.getKey());
            e.getValue().forEach((k, v) -> assertThat(p.getProperty(k)).as(e.getKey() + " " + k).isEqualTo(v));
        }
        // 默认包与 EN 一致
        Properties def = load("/i18n/messages.properties");
        Properties en = load("/i18n/messages_en.properties");
        for (String k : KEYS) {
            assertThat(def.getProperty(k)).as("messages.properties " + k).isEqualTo(en.getProperty(k));
        }
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void newPageTemplateUsesEditorsAndMapsVarTyped() throws Exception {
        String t = resource("/templates/admin/ops-notification-new.html");
        assertThat(count(t, "contenteditable=\"true\"")).isEqualTo(4);
        assertThat(count(t, "data-ins-var=")).isEqualTo(8);
        assertThat(t).doesNotContain("maxlength");
        for (String f : List.of("titleEn", "titleId", "bodyEn", "bodyId")) {
            assertThat(t).contains("<input type=\"hidden\" name=\"" + f + "\"")
                    .contains("data-ed=\"" + f + "\"").contains("data-error-name=\"" + f + "\"");
        }
        assertThat(t).contains("data-var-names=${varNamesJson}");
        Matcher steps = Pattern.compile("data-error-steps='([^']*)'").matcher(t);
        assertThat(steps.find()).isTrue();
        assertThat(steps.group(1)).contains("\"admin.err.ops-notify.var.typed\":1")
                .contains("\"admin.err.ops-notify.audience-empty-no-pet\":4");
        Matcher fields = Pattern.compile("data-error-fields='([^']*)'").matcher(t);
        assertThat(fields.find()).isTrue();
        assertThat(fields.group(1)).contains("\"admin.err.ops-notify.var.typed\":\"titleEn,titleId,bodyEn,bodyId\"");
    }
}
