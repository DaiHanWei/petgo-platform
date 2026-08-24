package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.support.ApiIntegrationTest;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * L1：管理后台 i18n 端到端（Story 1.6 AC1/AC2）。登录页（permitAll）默认中文，{@code ?lang=en} 切英文，
 * 且含语言切换入口。验证 MessageSource + LocaleChangeInterceptor 在 admin SSR 链生效。
 *
 * <p>Story 11.7 追加：印尼语（AC3）与英文基线回退（AC1）。
 */
class AdminLocaleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private MessageSource messageSource;

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

    /** AC3：{@code ?lang=id} 渲印尼语，且切换入口三语都在。 */
    @Test
    void loginPageSwitchesToIndonesianWithLangParam() throws Exception {
        String html = mvc.perform(get("/admin/login").param("lang", "id"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Masuk dengan Lark");           // id
        assertThat(html).contains("Konsol Operasional TailTopia");
        assertThat(html).doesNotContain("用 Lark 登录");
        assertThat(html).doesNotContain("Sign in with Lark");
        assertThat(html).contains("?lang=id");                    // 切换入口已放开
    }

    /** AC3：语言偏好写进 Cookie，下一次请求不带 {@code ?lang=} 也仍是印尼语。 */
    @Test
    void indonesianPreferenceIsRememberedInCookie() throws Exception {
        var cookie = mvc.perform(get("/admin/login").param("lang", "id"))
                .andReturn().getResponse().getCookie(AdminLocaleConfig.LOCALE_COOKIE);
        assertThat(cookie).as("语言 Cookie 未落盘").isNotNull();
        assertThat(cookie.getValue()).isEqualTo("id");

        String html = mvc.perform(get("/admin/login").cookie(cookie))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("Cookie 未生效，回落成默认中文").contains("Masuk dengan Lark");
    }

    /** 🛡 AC3：默认语言仍是 zh_CN —— 加了印尼语不改默认。 */
    @Test
    void defaultLocaleIsStillChinese() throws Exception {
        String html = mvc.perform(get("/admin/login"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("用 Lark 登录");
    }

    /**
     * 🔴 AC1：locale 文件缺这条键时，回退到**英文基线**，而不是把键名露在界面上。
     *
     * <p>取 {@code fr}（没有 {@code messages_fr.properties}）来跑：Spring 的解析链是
     * {@code messages_fr} → {@code messages}，与「{@code id} 缺某一条键」走的**完全是同一条路径**，
     * 只是缺得更彻底。这样就不必为了测试往生产资源文件里塞哨兵键。
     *
     * <p>钉住的是改动前的真实故障：那时缺键回退 code，界面上会出现 {@code admin.login.lark}。
     */
    @Test
    void unmatchedLocaleFallsBackToEnglishBaselineNotKeyName() throws Exception {
        String html = mvc.perform(get("/admin/login").param("lang", "fr"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("没回退到英文基线").contains("Sign in with Lark");
        assertThat(html).as("键名露到界面上了").doesNotContain("admin.login.lark");
    }

    /** 同一条回退链在 MessageSource 层的直接断言（不经模板，排除模板兜底的干扰）。 */
    @Test
    void messageSourceResolvesUnknownLocaleToBaseline() {
        assertThat(messageSource.getMessage("admin.login.lark", null, Locale.forLanguageTag("fr")))
                .isEqualTo("Sign in with Lark");
        assertThat(messageSource.getMessage("admin.login.lark", null, Locale.forLanguageTag("id")))
                .isEqualTo("Masuk dengan Lark");
    }
}
