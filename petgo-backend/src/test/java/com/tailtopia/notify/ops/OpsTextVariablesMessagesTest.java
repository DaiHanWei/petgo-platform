package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.ops.domain.OpsTextVariables;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * L0：变量显示名与后台三语文案一致（V1.4.2 Story 7.1 · AB-37）——文案改了而手打检测没跟上，运营手打的
 * 「{新说法}」就会原样发给用户。读 {@code admin.opsNotify.var.tag.*} 三语去括号 == {@link OpsTextVariables#DISPLAY_NAMES}。
 */
class OpsTextVariablesMessagesTest {

    private static final List<String> FILES = List.of("/i18n/messages_zh_CN.properties",
            "/i18n/messages_en.properties", "/i18n/messages_id.properties");

    @Test
    void displayNamesMatchVarTagCopy() throws Exception {
        Set<String> fromCopy = new HashSet<>();
        for (String f : FILES) {
            Properties p = new Properties();
            try (InputStream in = OpsTextVariablesMessagesTest.class.getResourceAsStream(f)) {
                assertThat(in).as(f).isNotNull();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : List.of("admin.opsNotify.var.tag.nickname", "admin.opsNotify.var.tag.petName")) {
                String v = p.getProperty(key);
                assertThat(v).as(f + " " + key).isNotBlank().startsWith("{").endsWith("}");
                String name = v.substring(1, v.length() - 1).trim();
                fromCopy.add(name);
                // 显示名本身就是手打检测的靶子：带括号必须被拦
                assertThat(OpsTextVariables.containsTyped(v)).as(f + " " + key).isTrue();
            }
        }
        assertThat(fromCopy).containsExactlyInAnyOrderElementsOf(OpsTextVariables.DISPLAY_NAMES);
    }
}
