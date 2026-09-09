package com.tailtopia.admin.shared.web;

/** htmx {@code HX-Trigger} 事件名（Story 2.3a AC5）——集中定义，模板 / JS / Java 三处同名。 */
public final class AdminHxEvents {

    /** 侧栏待办角标重新拉取（fragments/nav.html 监听 {@code admin:badge-refresh from:body}）。 */
    public static final String BADGE_REFRESH = "admin:badge-refresh";
    /** 关闭当前抽屉（Story 2.3b 的 admin-drawer.js 监听）。 */
    public static final String DRAWER_CLOSE = "admin:drawer-close";

    private AdminHxEvents() {
    }
}
