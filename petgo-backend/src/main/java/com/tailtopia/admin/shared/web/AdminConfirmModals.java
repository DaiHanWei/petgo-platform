package com.tailtopia.admin.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;

/**
 * 服务端确认弹层的统一出口（V1.4.2 shop-admin Story 1.2 · AD-3）：把 {@link AdminConfirmModal} 放进 model、
 * {@code HX-Retarget #confirm-host} + {@code innerHTML}，返回 {@code confirm-modal :: modal}。
 *
 * <p>商城页原生 {@code data-confirm} 一律改为「先 POST {@code …/confirm} 拿弹层，再由弹层提交原端点」，
 * 这里收口，避免每个 Controller 各写一遍响应头。宿主 {@code <div id="confirm-host">} 由页面自带。
 */
public final class AdminConfirmModals {

    public static final String HOST = "#confirm-host";
    public static final String VIEW = "admin/fragments/confirm-modal :: modal";

    private AdminConfirmModals() {
    }

    public static String render(Model model, HttpServletResponse response, AdminConfirmModal cm) {
        model.addAttribute("cm", cm);
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, HOST);
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return VIEW;
    }
}
