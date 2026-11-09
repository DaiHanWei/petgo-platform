package com.tailtopia.admin.aiquery.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 回答语言（spec D3）：只在新建对话时选择，按对话保存；落库为小写代码，与 agent 接口 {@code lang} 一致。
 */
public enum AiQueryLanguage {
    ZH("zh"), EN("en"), ID("id");

    private final String code;

    AiQueryLanguage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<AiQueryLanguage> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (AiQueryLanguage l : values()) {
            if (l.code.equalsIgnoreCase(code.trim())) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** 默认跟随后台界面语言（zh_CN → 中文，id → 印尼语，其余 → English）。 */
    public static AiQueryLanguage fromLocale(Locale locale) {
        if (locale == null) {
            return EN;
        }
        return switch (locale.getLanguage()) {
            case "zh" -> ZH;
            case "id", "in" -> ID;
            default -> EN;
        };
    }
}
