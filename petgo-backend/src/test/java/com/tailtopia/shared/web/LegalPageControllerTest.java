package com.tailtopia.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * L0 独立切片测试（无需 DB/Redis）：法律政策 H5 路由直出静态 HTML。
 *
 * <p>断言 {@code /privacy}、{@code /terms} 各返回 200、{@code text/html;charset=UTF-8}，
 * 且 body 含定稿政策的印尼语标志性文案——确认 classpath 资源真被装入并按 HTML 吐出。
 */
class LegalPageControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LegalPageController()).build();
    }

    @Test
    void privacy_returnsHtmlPolicy() throws Exception {
        mvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Kebijakan Privasi")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PT Lumos Digital Indonesia")));
    }

    @Test
    void terms_returnsHtmlPolicy() throws Exception {
        mvc.perform(get("/terms"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Syarat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BANI")));
    }

    @Test
    void accountDeletion_returnsHtmlPage() throws Exception {
        mvc.perform(get("/account-deletion"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Delete Your TailTopia Account")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cs@tailtopia.id")));
    }

    @Test
    void childSafety_returnsHtmlPage() throws Exception {
        mvc.perform(get("/child-safety"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Child Safety Standards")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CSAE")));
    }
}
