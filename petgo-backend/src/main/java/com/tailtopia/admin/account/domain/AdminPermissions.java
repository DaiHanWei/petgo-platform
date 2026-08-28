package com.tailtopia.admin.account.domain;

import java.util.List;

/**
 * 后台模块权限码全集（Story 1.5，PRD 附录 B）。全小写点分 {@code <模块>.<动作>}，
 * 直接用作 Spring authority 字符串。UI 勾选项 + 创建/改权限时的合法值校验都取自此处。
 *
 * <p>SUPER_ADMIN 隐式全权（经 {@code hasRole('SUPER_ADMIN') or hasAuthority('...')} 表达式判定，
 * 不往账号注入全集——见 {@code AdminUserDetailsService}）。{@code admin.manage_roles}（动态 RBAC）
 * 属后续版本，V1.0.0 不纳入。
 */
public final class AdminPermissions {
    public record PermissionGroup(String titleCode, List<String> permissionCodes) {
    }

    // 兽医（Epic 2）
    public static final String VET_VIEW = "vet.view";
    public static final String VET_CREATE = "vet.create";
    public static final String VET_EDIT = "vet.edit";
    public static final String VET_BAN = "vet.ban";
    public static final String VET_RESET_PASSWORD = "vet.reset_password";
    /** 资质录入/审核/续期（Story 2.7 新增，附录 B 扩展）。 */
    public static final String VET_QUALIFY = "vet.qualify";
    public static final String VET_QUALIFY_VIEW = "vet.qualify_view";

    // 用户账号治理（Epic 3）
    public static final String USER_VIEW = "user.view";
    public static final String USER_DEACTIVATE = "user.deactivate";
    public static final String USER_DELETE = "user.delete";
    /** 后台赠送 PawCoin（bug 20260728-389：运营向指定账户入账 BONUS）。 */
    public static final String USER_GRANT_PAWCOIN = "user.grant_pawcoin";
    /**
     * 用户标签管理（V1.1.6 Story 11.3 · AB-12A）。查看 / 编辑分两码。
     * ⚠️ 编辑权限**不要下放得比其它模块更宽** —— 该页的分配支持批量，
     * 是"一次影响很多用户"的动作。
     */
    public static final String USER_TAG_VIEW = "user.tag_view";
    public static final String USER_TAG_MANAGE = "user.tag_manage";
    /**
     * 用户手机号查看（V1.1.6 Story 11.4 · AB-11A）。
     *
     * <p>🔴 手机号是 **PII**，因此**不沿用「能看用户详情就能看手机号」** ——
     * 后台 PRD 原写「不新增独立权限项」，2026-08-21 决定改为独立权限。
     */
    public static final String USER_PHONE_VIEW = "user.phone_view";
    /**
     * 召回名单导出（Story 11.4）。
     *
     * <p>🔴 **与查看分开的第二个码**：查看是一次看一个人，导出是把 PII **批量带出系统**，
     * 风险高一档，而且带出之后平台再也管不到它。
     */
    public static final String USER_PHONE_EXPORT = "user.phone_export";

    // 内容审核（Epic 4）
    public static final String CONTENT_VIEW_REPORTS = "content.view_reports";
    public static final String CONTENT_VIEW = "content.view";
    public static final String CONTENT_TAKEDOWN = "content.takedown";
    public static final String CONTENT_RESTORE = "content.restore";
    public static final String CONTENT_PROACTIVE_TAKEDOWN = "content.proactive_takedown";
    /** 人工审核队列：查看 + 通过/拒绝（内容审核 Story 4.3；开关仍限 SUPER_ADMIN）。 */
    public static final String CONTENT_MANUAL_REVIEW = "content.manual_review";
    /**
     * 顶置管理（V1.1.6 Story 11.1 · AB-10A）。查看 / 编辑分两码 ——
     * 顶置直接改首页第一屏，能看不等于能改。
     * ⚠️ 新功能**不做权限回填迁移**：不给存量账号自动授予才是对的，由超管按需勾选。
     */
    public static final String CONTENT_PIN_VIEW = "content.pin_view";
    public static final String CONTENT_PIN_MANAGE = "content.pin_manage";
    /**
     * 内容装饰标签管理（V1.1.6 Story 11.2 · AB-10C）。同样查看 / 编辑分两码 ——
     * 🔴 打标不只是发荣誉，**它同时是个流量动作**（生效中的标签给该内容 ×1.3 曝光加权），
     * 能看不等于能改。
     */
    public static final String CONTENT_TAG_VIEW = "content.tag_view";
    public static final String CONTENT_TAG_MANAGE = "content.tag_manage";
    /**
     * 统一工单队列（V1.1.4 Story 3.1，AB-3D）：三类工单一个列表。
     *
     * <p>与 {@link #CONTENT_VIEW_REPORTS}（旧的举报队列）分开一个码，是因为统一视图里还有
     * <b>账号举报</b>与<b>账号标识字段审核</b>两类 —— 权限粒度跟着能看见的数据走，
     * 不能靠「反正旧码也能看举报」把两类新数据顺带放出去。
     */
    public static final String CONTENT_VIEW_TICKETS = "content.view_tickets";
    /**
     * 从工单队列执行账号级处置（V1.1.4 Story 3.2）：警告 / 封号 / 判为无需处置。
     *
     * <p>⚠️ <b>封号那一档额外还要 {@link #USER_DEACTIVATE}</b>（端点上是 and 关系）——
     * 停用账号本来就是一项受管能力，不能因为「他能看工单」就顺带把停用权也给了。
     */
    public static final String CONTENT_DISPOSE_ACCOUNT = "content.dispose_account";

    /**
     * 查看限流（降权）状态与到期时间（V1.1.6 Story 17.2 · AC5）。
     *
     * <p>🛡 与 {@link #CONTENT_THROTTLE_MANAGE} <b>分成两个码</b>：能看见谁在限流，
     * 和能动手限流/解除，是两件事 —— 客服要看，只有治理岗能动。
     */
    public static final String CONTENT_THROTTLE_VIEW = "content.throttle_view";

    /**
     * 执行限流与手动解除（V1.1.6 Story 17.2 · AC5）。
     *
     * <p>⚠️ 刻意<b>不</b>额外要 {@link #USER_DEACTIVATE}（封号那一档才要）：
     * 限流是降权、可逆、用户不可感知，把它抬到与停用账号同级会让这一档又变得没人敢用 ——
     * 而这一档存在的全部意义就是「不用在只说一句和直接封之间二选一」。
     */
    public static final String CONTENT_THROTTLE_MANAGE = "content.throttle_manage";

    // 问诊异常与会话（Epic 5）
    public static final String CONSULT_VIEW_ANOMALIES = "consult.view_anomalies";
    public static final String CONSULT_HANDLE = "consult.handle";
    public static final String CONSULT_VIEW_SESSIONS = "consult.view_sessions";

    // 评分（Epic 6）
    public static final String RATING_VIEW = "rating.view";

    // 客服工单（V1.1 Epic 4，Story 4.7）——处理/结案客服工单（FR-52）。
    public static final String SUPPORT_HANDLE = "support.handle";
    public static final String SUPPORT_VIEW = "support.view";

    // 退款两段审批（V1.1 Epic 4，Story 4.3，三级职责分离 A-1）
    /** 提交退款需求判定（客服）。 */
    public static final String REFUND_SUBMIT = "refund.submit";
    /** 审批退款申请（主管）。 */
    public static final String REFUND_APPROVE = "refund.approve";
    /** 执行退款打款（财务）。 */
    public static final String REFUND_PAYOUT = "refund.payout";
    public static final String REFUND_VIEW = "refund.view";

    // 咨询订单 / 收入（V1.1 Epic 9，Story 9-3/9-4）
    /** 兽医·AI 咨询订单只读查看。 */
    public static final String ORDER_VIEW = "order.view";
    public static final String ORDER_EDIT = "order.edit";
    /** 订单 / 收入统计导出。 */
    public static final String ORDER_EXPORT = "order.export";

    // 虚拟账号（V1.1 Epic 9，Story 9-8）
    /** 虚拟账号与种子批量上传管理。 */
    public static final String VIRTUAL_ACCOUNT_MANAGE = "virtual_account.manage";
    public static final String VIRTUAL_ACCOUNT_VIEW = "virtual_account.view";

    /**
     * 内容列表按当前筛选导出 CSV（2026-08-28）。
     *
     * <p>🔴 <b>与列表查看分开的第二个码</b>，同 Story 11.4 的口径：
     * 查看是一次看一屏，导出是把数据**批量带出系统**，风险高一档。
     *
     * <p>⚠️ <b>刻意起一个全新的码，不复用已撤销的 {@code content.stats_export}
     * 或历史死码 {@code content.export}</b>：那两个字符串可能仍留在某些存量账号的
     * permissions 里（前者刚撤、后者是 bug 20260731-440 摘掉的死码）。
     * 复用等于**给一批从未被评估过的账号静默发一项新能力** ——
     * 而这正是"导出单独一个码"想避免的事。新码零存量，只有 SUPER_ADMIN 与明确勾选的人有。
     */
    public static final String CONTENT_LIST_EXPORT = "content.list_export";

    // 内容互动积分统计（V1.1.6 Story 15.1 · AB-3G）—— **整页已于 2026-08-28 撤销**。
    // 产品判定：运营真正会看的只有点赞数，为它单开一页（两套统计口径 + CSV 导出）
    // 是把一个小问题做成了一个要先学会怎么用的工具。点赞数已作为一列并入「内容管理」。
    // 两个权限码 content.stats_view / content.stats_export 一并移除。
    // ⚠️ 已授予过它们的存量账号，permissions 里会剩下两个不再对应任何页面的字符串。
    //    它们不会生效（没有 @PreAuthorize 再引用），账号页也不会渲染（勾选区只遍历 GROUPS）。
    //    刻意**不写迁移去清**：那是一次为纯噪音数据承担的线上写操作，不划算。

    // 运营发布身份池（V1.1.6 Story 12.1 · AB-3I）
    /**
     * 以**运营真实账号**身份发布内容，以及该身份池的纳入 / 移除。
     *
     * <p>🔴 <b>与 {@link #VIRTUAL_ACCOUNT_MANAGE} 完全解耦，刻意独立</b>：
     * 能管虚拟账号 ≠ 能以真人身份发言。以运营真实账号误发的后果**不可撤回** ——
     * 内容会出现在那个真人的个人主页并推送给他的粉丝，事后删除也已经推送过了。
     *
     * <p>本版本**仅分配给超级管理员**，不下放（OQ-24）。权限码独立存在的意义是
     * 后续如需下放，只改权限配置、不改代码。
     */
    public static final String SEED_PUBLISH_AS_REAL = "seed.publish_as_real";

    // 运营配置（V1.1 Epic 9，Story 9-2/9-6）——定价 / PawCoin / 红色超额阈值等
    /** 配置查看。 */
    public static final String CONFIG_VIEW = "config.view";
    /** 配置编辑（定价 / PawCoin / 阈值）。 */
    public static final String CONFIG_EDIT = "config.edit";

    /**
     * 查看「算法参数」页（2026-08-26 产品决定）。
     *
     * <p>🔴 <b>刻意不给运营</b>。这一页是**打分公式内部参数**（权重、分位数、限流强度），
     * 与运营日常用的「顶置 / 打标 / 限流处置」不是一档东西 ——
     * 后者是模型出结果**之后**的业务规则（行业惯例里运营该有的那一层），
     * 前者改动的效果**没有任何机制能判定对错**（本平台无 A/B 实验基建）。
     * 所以这一页留给产品做校准，运营不开放。
     */
    public static final String CONFIG_ALGO_PARAM_VIEW = "config.algo_param_view";

    /**
     * 改「算法参数」（2026-08-26 产品决定）。
     *
     * <p>⚠️ 每一次改动都逐字段写入配置变更日志，并在页面上直接展示最近若干条 ——
     * 没有 A/B 的情况下，「谁在什么时候把哪个值从多少改成了多少」
     * 是唯一能与指标漂移对上的锚点。
     */
    public static final String CONFIG_ALGO_PARAM_EDIT = "config.algo_param_edit";

    /**
     * 查看分享奖励配置与当月消耗（V1.1.6 Story 18.3 · AC5）。
     *
     * <p>🛡 与 {@link #CONFIG_VIEW} 分开：分享奖励是增长侧的数，
     * 让增长看得到它不该顺带把兽医定价与分成比例也放出去。
     */
    public static final String CONFIG_SHARE_REWARD_VIEW = "config.share_reward_view";

    /**
     * 改分享奖励四项，含**总开关**（V1.1.6 Story 18.3 · AC5）。
     *
     * <p>🔴 与 {@link #CONFIG_EDIT} 分开的理由是**可用性**而不是洁癖：
     * 总开关存在的全部意义是「发现被刷要能立刻全线关掉」，
     * 而 {@code config.edit} 那道门管着兽医单价与分成比例 —— 只有极少数人过得去。
     * 把开关塞在那道门后面，"立刻"就根本做不到。
     */
    public static final String CONFIG_SHARE_REWARD_EDIT = "config.share_reward_edit";

    // 兽医分成月结对账（V1.1 Epic 9，Story 9-5）
    /** 月结对账查看。 */
    public static final String SETTLEMENT_VIEW = "settlement.view";
    /** 月结确认打款 / 归档（财务）。 */
    public static final String SETTLEMENT_PAYOUT = "settlement.payout";

    // 支付记录查询 / 风险观测（V1.1 Epic 9，Story 9-6）
    /** 支付记录通用查询。 */
    public static final String PAYMENT_VIEW = "payment.view";
    /** 红色超额只读监控 + 人工标记（无自动拦截，AB-7A）。 */
    public static final String RISK_VIEW = "risk.view";
    public static final String RISK_EDIT = "risk.edit";

    // 精选自营电商（V1.4.0，模块 10–13）——🔴 不默认授予任何既有运营角色（NFR-11）
    /** 商品与库存只读查看（模块 10）。 */
    public static final String SHOP_PRODUCT_VIEW = "shop.product_view";
    /** 商品创建/编辑、SKU 与规格维护（模块 10）。 */
    public static final String SHOP_PRODUCT_EDIT = "shop.product_edit";
    /** 🔒 进货价查看——商业敏感，默认仅财务与管理层。无此权限时服务端不下发该字段。 */
    public static final String SHOP_COST_VIEW = "shop.cost_view";
    /** 🔒 进货价编辑。 */
    public static final String SHOP_COST_EDIT = "shop.cost_edit";
    /** 库存管理页只读查看：实际/锁定/可售三列（模块 10 · AB-10C）。 */
    public static final String SHOP_INVENTORY_VIEW = "shop.inventory_view";
    /**
     * 库存变更：采购入库 / 报损 / 盘点调整（AB-10C）。
     *
     * <p>🔒 <b>采购入库另需 {@link #SHOP_COST_EDIT}</b>——进货单价按 S-9 不允许留空，而单价是商业
     * 敏感数据（2026-08-17 产品确认）。退货入库单价由系统带出，只需本权限。
     */
    public static final String SHOP_INVENTORY_EDIT = "shop.inventory_edit";

    // 电商订单履约（V1.4.0 模块 11 · AB-11A/B/D）——🔴 同样不默认授予任何既有角色（NFR-11）
    /** 电商订单列表与详情只读（AB-11A）。 */
    public static final String SHOP_ORDER_VIEW = "shop.order_view";
    /** 发货 / 标记已送达 / 异常订单处置（AB-11B、AB-11D）。 */
    public static final String SHOP_ORDER_FULFILL = "shop.order_fulfill";
    /**
     * 🔒 <b>按收件人电话模糊搜索全站订单</b>（AB-11A）。
     *
     * <p>单独成码而不并入 {@link #SHOP_ORDER_VIEW}：电话是 PII，按它反查能把「查单」变成
     * 「查人」（NFR-11）。给发货专员看单不等于给他全站按号码捞人的能力。
     * 每次使用都写审计（{@code SHOP_ORDER_SEARCHED_BY_PHONE}）。
     */
    public static final String SHOP_ORDER_PHONE_SEARCH = "shop.order_phone_search";

    /**
     * 🔒 <b>经营数据：毛利与对账</b>（V1.4.0 模块 13 · AB-13A / AB-13D，NFR-11）。
     *
     * <p>与 {@link #SHOP_COST_VIEW}（进货价）分开成两个码：看得到单个 SKU 的进货价，
     * 和看得到整盘生意的毛利与现金流，是两种不同量级的商业敏感。
     * 默认<b>仅财务与管理层</b>可见 —— 🔴 <b>不默认授予任何既有运营角色</b>。
     */
    public static final String SHOP_FINANCE_VIEW = "shop.finance_view";

    // 后台账号 / 审计（Epic 1）
    public static final String ADMIN_CREATE_ACCOUNT = "admin.create_account";
    public static final String ADMIN_VIEW_ACCOUNTS = "admin.view_accounts";
    public static final String ADMIN_DEACTIVATE = "admin.deactivate";
    public static final String ADMIN_VIEW_LOGS = "admin.view_logs";

    /** 按查看/编辑分组，供账号页勾选区展示。 */
    public static final List<PermissionGroup> GROUPS = List.of(
            new PermissionGroup("perm.group.view", List.of(
                    CONTENT_VIEW_REPORTS, CONTENT_VIEW_TICKETS, CONTENT_VIEW, CONTENT_PIN_VIEW, CONTENT_TAG_VIEW,
                    USER_VIEW, USER_TAG_VIEW, USER_PHONE_VIEW, USER_PHONE_EXPORT,
                    CONTENT_LIST_EXPORT, CONTENT_THROTTLE_VIEW,
                    VET_VIEW, VET_QUALIFY_VIEW, RATING_VIEW,
                    CONSULT_VIEW_ANOMALIES, CONSULT_VIEW_SESSIONS,
                    SUPPORT_VIEW, REFUND_VIEW,
                    CONFIG_VIEW, CONFIG_SHARE_REWARD_VIEW, CONFIG_ALGO_PARAM_VIEW, ORDER_VIEW, ORDER_EXPORT, SETTLEMENT_VIEW, PAYMENT_VIEW, RISK_VIEW,
                    VIRTUAL_ACCOUNT_VIEW,
                    ADMIN_VIEW_ACCOUNTS, ADMIN_VIEW_LOGS,
                    SHOP_PRODUCT_VIEW, SHOP_COST_VIEW, SHOP_INVENTORY_VIEW,
                    SHOP_ORDER_VIEW, SHOP_ORDER_PHONE_SEARCH, SHOP_FINANCE_VIEW)),
            new PermissionGroup("perm.group.edit", List.of(
                    CONTENT_TAKEDOWN, CONTENT_RESTORE, CONTENT_PROACTIVE_TAKEDOWN,
                    CONTENT_MANUAL_REVIEW, CONTENT_DISPOSE_ACCOUNT, CONTENT_THROTTLE_MANAGE,
                    CONTENT_PIN_MANAGE, CONTENT_TAG_MANAGE,
                    USER_DEACTIVATE, USER_DELETE, USER_GRANT_PAWCOIN, USER_TAG_MANAGE,
                    VET_CREATE, VET_EDIT, VET_BAN, VET_RESET_PASSWORD, VET_QUALIFY,
                    CONSULT_HANDLE,
                    SUPPORT_HANDLE, REFUND_SUBMIT, REFUND_APPROVE, REFUND_PAYOUT,
                    CONFIG_EDIT, CONFIG_SHARE_REWARD_EDIT, CONFIG_ALGO_PARAM_EDIT, ORDER_EDIT, SETTLEMENT_PAYOUT, RISK_EDIT,
                    VIRTUAL_ACCOUNT_MANAGE, SEED_PUBLISH_AS_REAL,
                    ADMIN_CREATE_ACCOUNT, ADMIN_DEACTIVATE,
                    SHOP_PRODUCT_EDIT, SHOP_COST_EDIT, SHOP_INVENTORY_EDIT,
                    SHOP_ORDER_FULFILL)));

    /** 全部合法权限码（UI 勾选项 + 校验白名单），保持模块分组顺序。 */
    public static final List<String> ALL = GROUPS.stream()
            .flatMap(group -> group.permissionCodes().stream())
            .toList();

    private AdminPermissions() {
    }

    /** 校验给定权限码是否合法（属于附录 B 全集）。 */
    public static boolean isValid(String code) {
        return ALL.contains(code);
    }
}
