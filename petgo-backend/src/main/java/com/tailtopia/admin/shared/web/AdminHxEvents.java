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
     * B8 用户标签：列表整表重拉（Story 8.2）。
     *
     * <p>⚠️ 与 {@link #TAG_LIST_REFRESH} **刻意分开**，尽管两页永远不会同时打开：
     * 事件名是写在模板 {@code hx-trigger} 里的字符串，同名意味着将来任何一页改了刷新语义，
     * 另一页会跟着一起变而没人察觉。
     */
    public static final String USER_TAG_LIST_REFRESH = "admin:user-tag-list-refresh";

    /** B9 运营发布身份 · 区块一：虚拟账号表整表重拉（Story 8.3；建号 / 改物种 / 启停都会改摘要条三格）。 */
    public static final String VIRTUAL_ACCOUNT_LIST_REFRESH = "admin:virtual-account-list-refresh";

    /**
     * B20 兽医账号：列表按当前筛选 + 当前页重拉（Story 9.1a）。
     *
     * <p>🔴 为什么不做单行 oob + 摘要条 oob：抽屉里那几个 POST 身上**没有筛选参数**，
     * 在服务端重算只能按全库算，而屏幕上的表格是筛选后的 —— 两个数摆在一起就是错的。
     * 由页面上的刷新槽带着当前筛选表单去重拉，是唯一能保证「表格与摘要条同一口径」的做法。
     */
    public static final String VET_LIST_REFRESH = "admin:vet-list-refresh";

    /**
     * B12 支付记录：列表按当前筛选 + 当前页重拉（Story 8.5，仅 stag 的模拟回调会发）。
     *
     * <p>⚠️ 这里**必须整表重拉、不做单行 oob**：模拟回调改的是状态，而摘要条的
     * 「已支付笔数 / 现金收入」跟着变，那两个数只有带上当前筛选条件重算才是对的
     * （汇总覆盖整个筛选结果，不是当前页）。
     */
    public static final String PAYMENT_LIST_REFRESH = "admin:payment-list-refresh";

    /**
     * 关掉当前打开的确认弹层并清空它的宿主（Story 8.3）。
     *
     * <p>🔴 为什么要一个事件而不是让响应把宿主换空：确认表单的 {@code hx-target} 必须指向
     * **弹层内部**那个错误槽 —— 否则 422 / 403 一回来就把整个弹层连同用户填的东西一起换掉，
     * 运营看不到「为什么没成功」。成功路径因此没法顺带把弹层清掉，只能靠这个事件。
     */
    public static final String CONFIRM_CLOSE = "admin:confirm-close";

    /**
     * B5 排期发布：排期表按当前筛选 + 当前页重拉（Story 7.5）。
     *
     * <p>⚠️ 改时间 / 取消都**不做单行 oob**：表按计划时间升序，改完时间那一行的位置会变；
     * 取消后那一行回到 DRAFT、根本不该留在这张表里。两种情况原位换行都会给出一张骗人的表。
     */
    public static final String SCHEDULE_LIST_REFRESH = "admin:schedule-list-refresh";

    /** B5 排期发布：抽屉按自身 URL 重拉（Story 7.5；与列表分开的理由同 {@link #CONTENT_DRAWER_REFRESH}）。 */
    public static final String SCHEDULE_DRAWER_REFRESH = "admin:schedule-drawer-refresh";

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
