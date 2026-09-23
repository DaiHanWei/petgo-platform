package com.tailtopia.admin.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * L0（bug 20260923-548）：审计页「动作说明」列按 action code 取三语文案。
 * <ul>
 *   <li>{@link AuditActions} 里每个常量在 zh_CN / en / id 三语都有文案（不回退成 code）；</li>
 *   <li>缺文案（如动态拼出的 {@code CONFIG_UPDATE_*}）回退显示 code 本身，不露键名、不抛异常。</li>
 * </ul>
 */
class AuditActionLabelsTest {

    private final AuditActionLabels labels = new AuditActionLabels(new AdminLocaleConfig().messageSource());

    private static List<String> allActionCodes() throws IllegalAccessException {
        List<String> codes = new ArrayList<>();
        for (Field f : AuditActions.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                f.setAccessible(true);
                codes.add((String) f.get(null));
            }
        }
        return codes;
    }

    @Test
    void everyAuditActionHasALabelInAllThreeLocales() throws Exception {
        List<String> codes = allActionCodes();
        assertThat(codes).hasSizeGreaterThan(50);
        for (Locale locale : AdminLocaleConfig.SUPPORTED_LOCALES) {
            List<String> missing = new ArrayList<>();
            for (String code : codes) {
                if (labels.label(code, locale).equals(code)) {
                    missing.add(code);
                }
            }
            assertThat(missing).as("%s 缺审计动作文案 admin.audit.action.<CODE>", locale).isEmpty();
        }
    }

    @Test
    void labelsFollowTheLocale() {
        assertThat(labels.label(AuditActions.CONTENT_TAKEN_DOWN, Locale.SIMPLIFIED_CHINESE)).isEqualTo("下架内容");
        assertThat(labels.label(AuditActions.CONTENT_TAKEN_DOWN, Locale.ENGLISH)).isEqualTo("Content taken down");
        assertThat(labels.label(AuditActions.CONTENT_TAKEN_DOWN, Locale.forLanguageTag("id"))).isEqualTo("Konten diturunkan");
        assertThat(labels.label(AuditActions.EMERGENCY_LOGIN_SUCCEEDED, Locale.ENGLISH)).doesNotContainPattern("[\\u4e00-\\u9fa5]");
    }

    @Test
    void unknownCodeFallsBackToTheCodeItself() {
        assertThat(labels.label("CONFIG_UPDATE_SOMETHING_NEW", Locale.ENGLISH)).isEqualTo("CONFIG_UPDATE_SOMETHING_NEW");
        assertThat(labels.label("CONFIG_UPDATE_SOMETHING_NEW", Locale.SIMPLIFIED_CHINESE)).isEqualTo("CONFIG_UPDATE_SOMETHING_NEW");
        assertThat(labels.label(null, Locale.ENGLISH)).isEmpty();
        assertThat(labels.label(" ", Locale.ENGLISH)).isEmpty();
    }
}
