package com.tailtopia.admin.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.GlobalExceptionHandler;
import com.tailtopia.support.TestMessages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.View;

/**
 * L0（standalone MockMvc）：htmx 请求下 AppException → 422 inline-error + HX-Reswap/HX-Retarget；表单校验 → 422；
 * AccessDenied → 403 forbidden（带所缺权限名）；非 htmx 维持现状（ProblemDetail / 原样重抛）。Story 2.3a AC2/AC3。
 */
class AdminBusinessExceptionAdviceTest {

    /** 测试控制器必须在 com.tailtopia.admin 包下（advice 的 basePackages）。 */
    @Controller
    static class ProbeController {
        @PostMapping("/admin/probe/app")
        @ResponseBody
        String app() {
            throw AppException.validation("不能停用自己的账号").code("admin.err.account.selfDeactivate");
        }

        @PostMapping("/admin/probe/denied")
        @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('admin.deactivate')")
        @ResponseBody
        String denied() {
            throw new AccessDeniedException("Access Denied");
        }

        static class Form {
            @NotBlank
            String name;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }
        }

        @PostMapping("/admin/probe/bind")
        @ResponseBody
        String bind(@Valid @ModelAttribute Form form) {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler fallback = new GlobalExceptionHandler(TestMessages.real(), DataSize.ofMegabytes(1));
        AdminBusinessExceptionAdvice advice = new AdminBusinessExceptionAdvice(TestMessages.real(), fallback);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(advice)
                .setViewResolvers((name, locale) -> (View) (model, req, resp) -> {
                    resp.setContentType("text/html");
                    resp.getWriter().write(name + "|" + model.get("message"));
                })
                .build();
    }

    @Test
    void htmxAppExceptionBecomes422InlineErrorFragmentWithRetarget() throws Exception {
        String body = mvc.perform(post("/admin/probe/app").header("HX-Request", "true").header("HX-Target", "row-7"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Reswap", "innerHTML"))
                .andExpect(header().string("HX-Retarget", "#row-7"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).startsWith("admin/fragments/tpl-shared :: inline-error|");
        assertThat(body).doesNotContain("admin.err.account.selfDeactivate"); // 已按 locale 解析成文案
    }

    @Test
    void htmxWithoutTargetFallsBackToInlineErrorAnchor() throws Exception {
        mvc.perform(post("/admin/probe/app").header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("HX-Retarget", "#admin-inline-error"));
    }

    @Test
    void plainRequestKeepsProblemDetail() throws Exception {
        String body = mvc.perform(post("/admin/probe/app"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"status\":422").contains("\"title\"");
    }

    @Test
    void htmxBindingErrorBecomes422() throws Exception {
        String body = mvc.perform(post("/admin/probe/bind").header("HX-Request", "true").param("name", " "))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).startsWith("admin/fragments/tpl-shared :: inline-error|name:");
    }

    @Test
    void htmxAccessDeniedBecomes403ForbiddenFragmentNamingThePermission() throws Exception {
        String body = mvc.perform(post("/admin/probe/denied").header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("HX-Reswap", "innerHTML"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).startsWith("admin/fragments/tpl-shared :: forbidden|");
        // 所缺权限名 = perm.admin.deactivate 的三语显示名（D-37 固定显示）
        assertThat(body).contains(TestMessages.real().get("perm.admin.deactivate"));
    }

    @Test
    void plainAccessDeniedIsRethrownForSecurityChain() {
        assertThatThrownBy(() -> mvc.perform(post("/admin/probe/denied")))
                .hasCauseInstanceOf(AccessDeniedException.class);
    }
}
