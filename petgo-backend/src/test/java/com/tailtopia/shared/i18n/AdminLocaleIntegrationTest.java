package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * L1：管理后台 i18n 端到端（Story 1.6 AC1/AC2；本次由双语扩到三语）。登录页（permitAll）默认中文，
 * {@code ?lang=en} / {@code ?lang=id} 切换，且含三个语言切换入口。
 * 验证 MessageSource + LocaleChangeInterceptor + locale 收敛在 admin SSR 链上真的生效。
 */
class AdminLocaleIntegrationTest extends ApiIntegrationTest {

    @Test
    void loginPageDefaultsToChineseAndHasLangSwitch() throws Exception {
        String html = mvc.perform(get("/admin/login"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("用 Lark 登录");      // 默认 zh_CN
        assertThat(html).contains("?lang=en");          // 语言切换入口
        assertThat(html).contains("?lang=zh_CN");
        assertThat(html).contains("?lang=id");
    }

    @Test
    void loginPageSwitchesToEnglishWithLangParam() throws Exception {
        String html = mvc.perform(get("/admin/login").param("lang", "en"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Sign in with Lark"); // en
        assertThat(html).doesNotContain("用 Lark 登录");
    }

    @Test
    void loginPageSwitchesToIndonesianWithLangParam() throws Exception {
        String html = mvc.perform(get("/admin/login").param("lang", "id"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Masuk dengan Lark");   // id
        assertThat(html).doesNotContain("用 Lark 登录");
        assertThat(html).doesNotContain("Sign in with Lark");
        // <html lang> 跟随 locale，而不是永远自称中文页。
        assertThat(html).contains("lang=\"id\"");
    }

    /**
     * 不受支持的语言必须收敛回默认语言，而不是把键名当文案渲染出来。
     * MessageSource 配了 useCodeAsDefaultMessage，没有收敛的话页面会出现 admin.login.lark 这种字样。
     */
    @Test
    void unsupportedLangFallsBackToDefaultInsteadOfLeakingKeys() throws Exception {
        String html = mvc.perform(get("/admin/login").param("lang", "fr"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("用 Lark 登录");
        assertThat(html).doesNotContain("admin.login.");
    }
}
