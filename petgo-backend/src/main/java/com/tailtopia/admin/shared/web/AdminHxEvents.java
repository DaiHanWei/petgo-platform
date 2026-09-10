package com.tailtopia.admin.shared.web;

/** htmx {@code HX-Trigger} 事件名（Story 2.3a AC5）——集中定义，模板 / JS / Java 三处同名。 */
public final class AdminHxEvents {

    /** 侧栏待办角标重新拉取（fragments/nav.html 监听 {@code admin:badge-refresh from:body}）。 */
    public static final String BADGE_REFRESH = "admin:badge-refresh";
    /** 关闭当前抽屉（Story 2.3b 的 admin-drawer.js 监听）。 */
    public static final String DRAWER_CLOSE = "admin:drawer-close";

    // ===== 各页的局部刷新事件（模板里的 hx-trigger="… from:body" 与这里逐字同名）=====

    /** B1 内容管理：列表按当前筛选 + 当前页重拉表格与摘要条（Story 7.1）。 */
    public static final String CONTENT_LIST_REFRESH = "admin:content-list-refresh";

    /**
     * B1 内容管理：抽屉按自身 URL 重拉（Story 7.1）。
     *
     * <p>⚠️ 与 {@link #CONTENT_LIST_REFRESH} <b>分成两个</b>：本页三个处置端点的响应体就是重渲染后的抽屉，
     * 只需要刷列表；合成一个会让抽屉刚渲染完又被重拉一遍（多一次请求 + 闪一下 + 丢掉刚带回的状态）。
     * 只有共用控制器的限流两个端点（响应体只是 toast）两个都发。
     */
    public static final String CONTENT_DRAWER_REFRESH = "admin:content-drawer-refresh";

    /** B2 评论巡查：列表按当前筛选 + 当前页重拉（Story 7.2；抽屉同样由处置响应体直接带回，不需要事件）。 */
    public static final String COMMENT_LIST_REFRESH = "admin:comment-list-refresh";

    /** B3 顶置管理：列表按当前筛选 + 当前页重拉（Story 7.3；新建成功也发它 —— 新行得靠整表重拉才会出现）。 */
    public static final String PIN_LIST_REFRESH = "admin:pin-list-refresh";

    /** B4 内容标签：列表整表重拉（Story 7.4；建标签 / 改标签 / 上下线 / 打标 / 取消打标都会改「生效中分配数」与摘要条）。 */
    public static final String TAG_LIST_REFRESH = "admin:tag-list-refresh";

    /**
     * 让前端打开某个抽屉，载荷 {@code {"url": "<抽屉 URL>", "id": <对象 id>}}
     * （V1.3.0 Story 7.4 新增 · AC4：新建标签成功后
     * 直接停在新标签抽屉的「分配记录」页签 —— 建完标签紧接着就是给它加内容）。
     *
     * <p>⚠️ 与「把抽屉体换掉」不是一回事：换抽屉体不会更新 {@code admin-drawer.js} 里记的当前对象，
     * 地址栏的 {@code ?open=} 也不会跟着走，刷新就回到新建态。所以走事件、让 JS 走正常的 open 流程。
     */
    public static final String DRAWER_OPEN = "admin:drawer-open";

    private AdminHxEvents() {
    }
}
