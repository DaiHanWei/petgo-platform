package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.OpenedPrecedent;
import com.tailtopia.shop.returns.domain.RejectDisposal;
import com.tailtopia.shop.returns.domain.ReturnLine;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.repository.OpenedPrecedentRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.returns.service.RefundExecutionService;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shop.service.InventoryMovementService;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台退货处理（Story 5.3 审核队列 AB-12A · 5.4 质检与入库 AB-12B · 5.6 判例库 AB-12D）。
 *
 * <p>🔴 <b>不新建审核通道</b>（AB-12A）：权限沿用既有退款审批三级
 * （{@code refund.view} / {@code refund.approve} / {@code refund.payout}），
 * 判定在控制器。新建一套平行的权限体系会让「谁能批退款」这个问题有两个互相矛盾的答案。
 *
 * <p>🔴 <b>「是否批准退货」与「运费由谁承担」分开记录</b>（AB-12A）——
 * 运费归属直接影响退款金额。本类里运费归属由 {@code ReturnType} / {@code isFullReturn}
 * <b>自动得出</b>，客服<b>没有</b>调整它的入口：手工可调等于打开凑单-退货套利的口子（C-12）。
 */
@Service
public class AdminReturnService {

    private static final Logger log = LoggerFactory.getLogger(AdminReturnService.class);

    private final ReturnRequestRepository returns;
    private final ReturnRequestService requests;
    private final RefundExecutionService refunds;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final InventoryMovementService movements;
    private final OpenedPrecedentRepository precedents;
    private final AdminAuditService audit;
    private final NotificationService notifications;

    /** V1.3.0 Story 10.1：五步进度条「批准」一步要显示操作人名（实体只存 {@code reviewed_by} 这个 id）。 */
    private final com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts;

    public AdminReturnService(ReturnRequestRepository returns, ReturnRequestService requests,
            RefundExecutionService refunds, ShopOrderRepository orders,
            ShopOrderLineRepository orderLines, InventoryMovementService movements,
            OpenedPrecedentRepository precedents, AdminAuditService audit,
            NotificationService notifications,
            com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts) {
        this.adminAccounts = adminAccounts;
        this.returns = returns;
        this.requests = requests;
        this.refunds = refunds;
        this.orders = orders;
        this.orderLines = orderLines;
        this.movements = movements;
        this.precedents = precedents;
        this.audit = audit;
        this.notifications = notifications;
    }

    // ---------- 5.3 审核队列 ----------

    @Transactional(readOnly = true)
    public List<ReturnRequest> queue(ReturnStatus status, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return status == null
                ? returns.findAllByOrderByCreatedAtDescIdDesc(page)
                : returns.findByStatusOrderByCreatedAtAscIdAsc(status, page);
    }

    // ---------- V1.3.0 Story 10.1：A7 工作台（模板 A）的只读派生查询 ----------

    /**
     * A7 五页签（Story 10.1 AC1）。一个页签对应<b>一组</b>状态，所以不能用只收单个状态的
     * {@link #queue(ReturnStatus, int)}。
     *
     * <p>🔴 状态映射取自 {@link ReturnStatus} 的<b>全集</b>：九个状态每一个都必须落在某个页签里，
     * 否则那条申请在界面上彻底消失（列表页时代有个「全部」兜底，工作台没有）。
     * {@link #ALL_TABBED} 在 {@link #of(ReturnStatus)} 里按页签顺序查找，漏一个就会回落到
     * {@code PENDING}，而不是静默丢弃。
     */
    public enum Tab {
        PENDING("pending", ReturnStatus.PENDING_REVIEW),
        SHIPBACK("shipback", ReturnStatus.AWAIT_SHIPBACK),
        INSPECT("inspect", ReturnStatus.INSPECTING),
        REFUND("refund", ReturnStatus.REFUNDING, ReturnStatus.REFUND_FAILED),
        CLOSED("closed", ReturnStatus.REFUNDED, ReturnStatus.CLOSED, ReturnStatus.REJECTED,
                ReturnStatus.WITHDRAWN);

        private final String param;
        private final List<ReturnStatus> statuses;

        Tab(String param, ReturnStatus... statuses) {
            this.param = param;
            this.statuses = List.of(statuses);
        }

        public String param() {
            return param;
        }

        public List<ReturnStatus> statuses() {
            return statuses;
        }

        /** 宽松解析：值不对（有人手改 URL）当作默认页签，不为此让整页 500。 */
        public static Tab of(String raw) {
            if (raw != null) {
                for (Tab t : values()) {
                    if (t.param.equalsIgnoreCase(raw.trim())) {
                        return t;
                    }
                }
            }
            return PENDING;
        }

        /** 深链未指明页签时按该申请当前状态落页签 —— 否则行不在左栏，右栏开了也选不中。 */
        public static Tab of(ReturnStatus status) {
            for (Tab t : values()) {
                if (t.statuses.contains(status)) {
                    return t;
                }
            }
            return PENDING;
        }
    }

    /** 九个状态必须被五个页签**恰好覆盖一次**（{@code AdminReturnTabsTest} 断言）。 */
    public static final List<ReturnStatus> ALL_TABBED = java.util.Arrays.stream(Tab.values())
            .flatMap(t -> t.statuses().stream()).toList();

    /**
     * 一页队列（先进先出：{@code created_at ASC, id ASC}，AC1）。
     *
     * <p>筛选：退货类型 / 整单退，两者都可为空 = 不筛。空值不进谓词（不是绑一个 null 参数）。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ReturnRequest> page(Tab tab, ReturnType type,
            Boolean fullReturn, int page, int size) {
        var sort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("createdAt"),
                org.springframework.data.domain.Sort.Order.asc("id"));
        return returns.findAll(spec(tab, type, fullReturn),
                PageRequest.of(Math.max(page, 0), Math.max(1, size), sort));
    }

    /**
     * 五个页签的计数（AC1「各带计数」）。
     *
     * <p>🔴 计数<b>跟着筛选走</b>：筛了「质量问题」却显示未筛的总数，会出现「待质检 5」配一张空队列
     * —— 运营只会当成加载失败。代价是每次开页多四次 count，队列表本来就小。
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> counts(ReturnType type, Boolean fullReturn) {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Tab t : Tab.values()) {
            out.put(t.param(), returns.count(spec(t, type, fullReturn)));
        }
        return out;
    }

    /**
     * 同页签里「下一条」的 token（处置成功后自动选中，AC2）。
     *
     * <p>处置完那条<b>已经离开本页签</b>（状态变了），所以这里取的就是新的队首；
     * 仍然命中自己（幂等重放 / 状态没变）时跳过它，避免原地打转。
     */
    @Transactional(readOnly = true)
    public String nextToken(Tab tab, ReturnType type, Boolean fullReturn, String excludeToken) {
        for (ReturnRequest r : page(tab, type, fullReturn, 0, 2).getContent()) {
            if (!r.getPublicToken().equals(excludeToken)) {
                return r.getPublicToken();
            }
        }
        return null;
    }

    private static org.springframework.data.jpa.domain.Specification<ReturnRequest> spec(
            Tab tab, ReturnType type, Boolean fullReturn) {
        return (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new java.util.ArrayList<>();
            ps.add(root.get("status").in(tab.statuses()));
            if (type != null) {
                ps.add(cb.equal(root.get("returnType"), type));
            }
            if (fullReturn != null) {
                ps.add(cb.equal(root.get("fullReturn"), fullReturn));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /**
     * 右栏①「五步进度条」的一步（AC1）。
     *
     * @param key   i18n 后缀（submit / approve / shipback / inspect / refund）
     * @param state {@code done} / {@code current} / {@code todo} / {@code skip} / {@code rejected}
     * @param actor 操作人显示名；🔴 实体上<b>只有「批准」这一步存了操作人</b>（{@code reviewed_by}），
     *              寄回 / 质检 / 退款三步的操作人只在审计日志里 —— 本 story「零后端功能改动」，不为此加列
     * @param at    完成时刻（未完成为 null）
     */
    public record Step(String key, String state, String actor, java.time.Instant at) {
    }

    /**
     * 五步进度（申请 → 批准 → 寄回 → 质检 → 退款）。
     *
     * <p>🔴 拒收 / 发货前取消<b>跳过寄回与质检</b>（见 {@link #approve}）——
     * 这两步必须显式标 {@code skip}，否则界面上会永远停在「待寄回」而实际早已进入退款执行。
     *
     * <p>🔴 <b>{@code REJECTED} 有两个来源，且都不能画成「已批准」</b>：
     * 审核驳回（{@link #reject}）与质检不通过（{@link #failInspection}）——
     * 两者都把状态置成 {@code REJECTED}，也都写 {@code reviewed_by/reviewed_at}。
     * 如果只按「不是 PENDING_REVIEW 就算 done」分，被驳回的单子会渲染成
     * 「批准 · 某某 · 某时」并带完成样式 —— 读起来就是「已批准，等用户寄回」，
     * 而它其实是终态被拒。用 {@code inspectionPassed} 区分是哪一段拒的：
     * 质检走过就一定非 null（通过 = TRUE，不通过 = FALSE）。
     *
     * <p>同理 {@code WITHDRAWN}（用户自己撤回，可能从没人审过）：{@code reviewedAt} 为空就是没审过，
     * 标 {@code skip} 而不是 {@code done} —— AC1 说「完成步带操作人 + 时间」，
     * 把没发生过的步标成完成是审计口径上的错误陈述，不只是样式问题。
     */
    @Transactional(readOnly = true)
    public List<Step> steps(ReturnRequest r) {
        ReturnStatus s = r.getStatus();
        boolean skipPhysical = r.getReturnType() != null && r.getReturnType().isUndelivered();
        boolean inspected = r.getInspectionPassed() != null;
        boolean failedInspection = Boolean.FALSE.equals(r.getInspectionPassed());
        String reviewer = r.getReviewedBy() == null ? null
                : adminAccounts.findById(r.getReviewedBy())
                        .map(a -> a.getDisplayName()).orElse(null);

        String approveState;
        if (s == ReturnStatus.PENDING_REVIEW) {
            approveState = "current";
        } else if (r.getReviewedAt() == null) {
            approveState = "skip";              // 从没人审过（用户撤回）
        } else if (s == ReturnStatus.REJECTED && !inspected) {
            approveState = "rejected";          // 审核这一步就驳回了
        } else {
            approveState = "done";
        }

        List<Step> out = new java.util.ArrayList<>();
        out.add(new Step("submit", "done", null, r.getCreatedAt()));
        out.add(new Step("approve", approveState, reviewer, r.getReviewedAt()));
        out.add(new Step("shipback", skipPhysical ? "skip"
                : "current".equals(approveState) || "skip".equals(approveState)
                        || "rejected".equals(approveState) ? "todo"
                : s == ReturnStatus.AWAIT_SHIPBACK ? "current"
                : r.getShipbackTrackingNo() != null ? "done" : "todo",
                null, null));
        out.add(new Step("inspect", skipPhysical ? "skip"
                : s == ReturnStatus.INSPECTING ? "current"
                : failedInspection ? "rejected"
                : inspected ? "done" : "todo",
                null, null));
        out.add(new Step("refund",
                s == ReturnStatus.REFUNDED ? "done"
                        : (s == ReturnStatus.REFUNDING || s == ReturnStatus.REFUND_FAILED)
                                ? "current" : "todo",
                null, r.getRefundedAt()));
        return List.copyOf(out);
    }

    @Transactional(readOnly = true)
    public ReturnRequest require(String returnToken) {
        return returns.findByPublicToken(returnToken)
                .orElseThrow(() -> AppException.notFound("退货申请不存在").code("admin.err.return.notFound"));
    }

    /**
     * 批准。拒收 / 发货前取消<b>跳过寄回与质检</b>，直接进入退款执行。
     *
     * <p>🔴 本方法<b>没有运费归属参数</b> —— 那是刻意的（C-12 / AB-12A）：
     * 回程运费归属由退货类型得出，去程运费是否退由勾选范围得出，客服没有可调的旋钮。
     */
    @Transactional
    public ReturnRequest approve(String returnToken, long adminId) {
        ReturnRequest r = require(returnToken);
        r.approve(adminId);
        returns.save(r);
        audit.record(adminId, AuditActions.SHOP_RETURN_REVIEWED, "SHOP_RETURN", returnToken,
                "批准退货：类型=%s 整单退=%s 回程运费=%s 去程运费退回=%s".formatted(
                        r.getReturnType(), r.isFullReturn(), r.getReturnShipBearer(),
                        r.isOutboundFeeRefundable()));
        notifyUser(r, "你的退货申请已通过审核。");
        return r;
    }

    /** 驳回。🔴 理由必填并回告用户（复用 FR-52A）；订单回到申请前状态（SPEC-6 ②）。 */
    @Transactional
    public ReturnRequest reject(String returnToken, String reason, long adminId) {
        ReturnRequest r = require(returnToken);
        r.reject(adminId, reason);
        returns.save(r);
        requests.restoreOrderStatus(r);
        audit.record(adminId, AuditActions.SHOP_RETURN_REVIEWED, "SHOP_RETURN", returnToken,
                "驳回退货：" + reason);
        notifyUser(r, "你的退货申请未通过。原因：" + reason);
        return r;
    }

    /**
     * 🔴 后台手工创建退货申请时同样要校验「同订单仅一张进行中申请」（C-12）。
     *
     * <p>用户端入口层的拦截挡不住后台 —— 而后台恰恰是最可能绕过去的那一侧。
     * 这里做的是<b>前置提示</b>；真正的强制仍在库级部分唯一索引上。
     */
    @Transactional(readOnly = true)
    public void requireNoActiveRequest(long orderId) {
        if (requests.hasActiveRequest(orderId)) {
            throw AppException.conflict("该订单已有进行中的退货申请").code("admin.err.return.alreadyInProgress");
        }
    }

    // ---------- 5.4 质检与入库 ----------

    /**
     * 用户已寄回 → 登记运单，进入质检。
     *
     * <p>运费先由用户垫付；平台承担的情形在退款执行时按<b>实际运单金额</b>一并返还（S-7）。
     */
    @Transactional
    public ReturnRequest registerShipback(String returnToken, String carrier, String trackingNo,
            Long fee, long adminId) {
        ReturnRequest r = require(returnToken);
        r.registerShipback(carrier, trackingNo, fee);
        returns.save(r);
        // 🔒 运单号非 PII 可记；用户地址/电话不进摘要
        audit.record(adminId, AuditActions.SHOP_RETURN_INSPECTED, "SHOP_RETURN", returnToken,
                "登记寄回运单：%s %s".formatted(carrier, trackingNo));
        return r;
    }

    /**
     * 质检通过 → 触发<b>退货入库</b>并进入退款执行。
     *
     * <p>🔴 <b>只有质检通过的退货才进入可售库存</b>，且以<b>退货入库批次</b>入库
     * （与采购入库区分，二次销售风险不同）。S-9：采购单号填<b>原订单号</b>、
     * 进货单价取该 SKU <b>最近一次采购入库单价</b>，🔴 <b>不允许留空</b> ——
     * 留空会让「钱已退、货已回、系统里不存在」，并污染 AB-13C 的资金占用读数。
     */
    @Transactional
    public ReturnRequest passInspection(String returnToken, String note, String photoKeys,
            long adminId) {
        ReturnRequest r = require(returnToken);
        ShopOrder order = orders.findById(r.getShopOrderId()).orElseThrow();
        r.passInspection(note, photoKeys);
        returns.save(r);

        for (ReturnLine rl : requests.linesOf(r.getId())) {
            var line = orderLines.findById(rl.getOrderLineId()).orElseThrow();
            movements.receiveReturn(line.getSkuId(), rl.getQty(), order.getPublicToken(),
                    LocalDate.now(), adminId);
        }
        audit.record(adminId, AuditActions.SHOP_RETURN_INSPECTED, "SHOP_RETURN", returnToken,
                "质检通过并以退货入库批次入库（原订单号 %s）".formatted(order.getPublicToken()));
        notifyUser(r, "你寄回的商品已通过质检，退款正在处理。");
        return r;
    }

    /**
     * 质检不通过 → 驳回。
     *
     * <p>🔴 <b>S-10：{@code REJECTED} 不再是纯终态</b> —— 用户的货已经寄出来了，
     * 必须同时记下<b>处置方式</b>（退回用户 / 报损），并在用户端展示驳回原因 + 质检照片。
     * 选「退回用户」时<b>回寄运费由平台承担</b>：是平台判定驳回，不应再让用户付。
     *
     * <p>⚠️ <b>不通过的商品不进可售库存</b> —— 这正是「只有质检通过才入库」的另一半。
     */
    @Transactional
    public ReturnRequest failInspection(String returnToken, String note, String photoKeys,
            RejectDisposal disposal, String shipBackTrackingNo, long adminId) {
        ReturnRequest r = require(returnToken);
        if (disposal == RejectDisposal.RETURN_TO_USER
                && (shipBackTrackingNo == null || shipBackTrackingNo.isBlank())) {
            throw AppException.validation("选择「退回用户」时必须填写回寄单号").code("admin.err.return.trackingRequired");
        }
        r.failInspection(note, photoKeys, disposal, shipBackTrackingNo);
        returns.save(r);
        requests.restoreOrderStatus(r);
        audit.record(adminId, AuditActions.SHOP_RETURN_INSPECTED, "SHOP_RETURN", returnToken,
                "质检不通过：%s；处置=%s".formatted(note, disposal));
        notifyUser(r, "你寄回的商品质检未通过。原因：" + note);
        return r;
    }

    // ---------- 5.5 退款执行（后台入口） ----------

    @Transactional
    public RefundExecutionService.Outcome executeRefund(String returnToken, long adminId) {
        ReturnRequest before = require(returnToken);
        boolean firstExecution = before.getStatus() == ReturnStatus.REFUNDING;
        var out = refunds.execute(returnToken);
        // 🔴 发货前取消：货从未出库，但付款时已 commit 扣了实际库存 —— 退款执行的同时按原订单号
        //    以退货入库批次回补，否则 actual 长期偏低（幻影缺货）。只在首次执行时回补（重复点击不重复入库）。
        //    ⚠️ 拒收（REFUSED_ON_DELIVERY）不在这里回补：货在承运商手里，何时入库是运营决定。
        if (firstExecution && before.getReturnType() == ReturnType.CANCEL_BEFORE_SHIPMENT) {
            ShopOrder order = orders.findById(before.getShopOrderId()).orElseThrow();
            for (ReturnLine rl : requests.linesOf(before.getId())) {
                var line = orderLines.findById(rl.getOrderLineId()).orElseThrow();
                movements.receiveReturn(line.getSkuId(), rl.getQty(), order.getPublicToken(),
                        LocalDate.now(), adminId);
            }
        }
        audit.record(adminId, AuditActions.SHOP_RETURN_REFUNDED, "SHOP_RETURN", returnToken,
                "退款执行：PawCoin 段=%d 现金段=%d 补偿溢价=%d 激励溢价=%d 回程运费返还=%d"
                        .formatted(out.coinRefunded(), out.cashRefunded(),
                                out.compensationPremium(), out.incentivePremium(),
                                out.shipbackReimbursed()));
        ReturnRequest r = require(returnToken);
        notifyUser(r, "退款已处理完成。");
        return out;
    }

    @Transactional(readOnly = true)
    public RefundExecutionService.Quote quote(String returnToken) {
        return refunds.quote(returnToken);
    }

    // ---------- 5.6 判例库 ----------

    @Transactional
    public OpenedPrecedent addPrecedent(String situation, boolean judgedOpened, String rationale,
            String evidenceKeys, Long returnRequestId, long adminId) {
        OpenedPrecedent p = precedents.save(OpenedPrecedent.of(situation, judgedOpened, rationale,
                evidenceKeys, returnRequestId, adminId));
        audit.record(adminId, AuditActions.SHOP_RETURN_PRECEDENT_ADDED, "SHOP_PRECEDENT",
                String.valueOf(p.getId()),
                "沉淀开封判例：%s → %s".formatted(situation, judgedOpened ? "算开封" : "不算开封"));
        return p;
    }

    @Transactional(readOnly = true)
    public List<OpenedPrecedent> searchPrecedents(String q, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        return q == null || q.isBlank()
                ? precedents.findAllByOrderByCreatedAtDescIdDesc(page)
                : precedents.search(q.trim(), page);
    }

    // ---------- 内部 ----------

    /** 🔒 站内信只说进展与原因，不带金额明细、不带地址。 */
    private void notifyUser(ReturnRequest r, String body) {
        try {
            notifications.send(r.getUserId(), NotificationType.SHOP_RETURN_UPDATED,
                    "退货进度更新", body, NotificationType.SHOP_RETURN_UPDATED.name(),
                    r.getPublicToken());
        } catch (RuntimeException e) {
            log.warn("退货进度站内信发送失败（不回滚处置）return={} cause={}",
                    r.getPublicToken(), e.getClass().getSimpleName());
        }
    }
}
