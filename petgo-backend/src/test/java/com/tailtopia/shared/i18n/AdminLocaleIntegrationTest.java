package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * L1：管理后台 i18n 端到端（Story 1.6 AC1/AC2）。登录页（permitAll）默认中文，{@code ?lang=en} 切英文，
 * 且含语言切换入口。验证 MessageSource + LocaleChangeInterceptor 在 admin SSR 链生效。
 */
class AdminLocaleIntegrationTest extends ApiIntegrationTest {

    @Test
    void loginPageDefaultsToChineseAndHasLangSwitch() throws Exception {
        String html = mvc.perform(get("/admin/login"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("用 Lark 登录");      // 默认 zh_CN
        assertThat(html).contains("?lang=en");          // 语言切换入口
        assertThat(html).contains("?lang=zh_CN");
    }

    @Test
    void loginPageSwitchesToEnglishWithLangParam() throws Exception {
        String html = mvc.perform(get("/admin/login").param("lang", "en"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Sign in with Lark"); // en
        assertThat(html).doesNotContain("用 Lark 登录");
    }
}
