package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台电商订单履约操作（Story 4.2，AB-11B）。
 *
 * <p>本类只做三件事：<b>调用领域服务 → 写审计 → 发通知</b>。
 * 履约状态机本身在 {@link ShopOrderFulfillmentService}（Story 4.1）——后台是它的一个调用方，
 * 不是它的第二份实现。
 *
 * <p>🔒 <b>NFR-5 的日志与审计口径</b>：承运商与物流单号<b>可记</b>（非 PII）；
 * 同上下文的收件人姓名 / 电话 / 详细地址<b>严禁</b>写入日志或审计摘要 ——
 * 审计表是永久保留、无 TTL 的（V34），写进去就再也拿不出来。
 */
@Service
public class AdminShopOrderService {

    private static final Logger log = LoggerFactory.getLogger(AdminShopOrderService.class);

    private final ShopOrderFulfillmentService fulfillment;
    private final ShopOrderRepository orders;
    private final AdminAuditService audit;
    private final NotificationService notifications;

    /** V1.3.0 Story 10.2：摘要条一条 SQL 出三个数（见 {@link #summary}）。 */
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public AdminShopOrderService(ShopOrderFulfillmentService fulfillment,
            ShopOrderRepository orders, AdminAuditService audit,
            NotificationService notifications,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.fulfillment = fulfillment;
        this.orders = orders;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    /**
     * 发货：登记包裹 → 订单转 {@code SHIPPED} → 审计 → 推送。
     *
     * <p>✅ S-2 一单多包：重复调用即登记第 2..N 个包裹，每条独立承运商 / 单号 / 承运成本。
     *
     * <p>⚠️ <b>承运成本必填</b>（S-11）：不录则 AB-13A 缺行、假设 A-19（自营毛利成立）不可验证。
     * 允许 0（自提 / 免运协议价），但必须是一个明确的 0，不是"忘了填"。
     */
    @Transactional
    public Shipment ship(String orderToken, String carrierRaw, String trackingNo,
            Long carrierCost, Long actorAccountId) {
        if (carrierCost == null) {
            throw AppException.validation("请填写承运成本（S-11：不录则毛利看板缺行）").code("admin.err.shopOrder.carrierCostRequired");
        }
        Carrier carrier = Carrier.parse(carrierRaw);
        Shipment shipment = fulfillment.ship(orderToken, carrier, trackingNo, carrierCost);
        ShopOrder order = orders.findByPublicToken(orderToken).orElseThrow();

        // 🔒 只记承运商 + 单号；收件人信息一个字都不进审计
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_SHIPPED, "SHOP_ORDER", orderToken,
                "发货：承运商=%s 物流单号=%s".formatted(carrier.name(), shipment.getTrackingNo()));

        notifyShipped(order);
        return shipment;
    }

    /**
     * 出口①：运营手动「标记已送达」（SPEC-2 兜底）。
     *
     * <p>🔴 <b>留存操作人与时间</b>：走统一审计（哈希链 append-only），不另建一张操作记录表。
     */
    @Transactional
    public void markDelivered(String orderToken, Long actorAccountId) {
        fulfillment.markDeliveredByAdmin(orderToken);
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_MARKED_DELIVERED, "SHOP_ORDER",
                orderToken, "运营手动标记已送达（SPEC-2 出口①）");
    }

    /** 逐包裹标记送达（S-2：订单需全部包裹送达才转 DELIVERED）。 */
    @Transactional
    public void markPackageDelivered(String orderToken, long shipmentId, Long actorAccountId) {
        boolean orderClosed = fulfillment.markShipmentDelivered(orderToken, shipmentId);
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_MARKED_DELIVERED, "SHOP_ORDER",
                orderToken, "标记单个包裹已送达；整单是否转已送达=" + orderClosed);
    }

    // ---------- 🔒 按电话搜索（Story 4.3，AB-11A / NFR-11） ----------

    /**
     * 按收件人电话模糊搜索全站订单。
     *
     * <p>🔒 <b>三道处置</b>（OQ-41 未拍板前的骨架，脱敏口径留配置位、不写死）：
     * <ol>
     *   <li><b>独立权限位</b> {@code shop.order_phone_search} —— 判定在控制器；</li>
     *   <li><b>每次调用写审计</b>，无论命中与否 —— 「查了没查到」同样是一次对 PII 的访问；</li>
     *   <li>🔴 <b>审计摘要绝不含号码本身</b>，只记命中条数与查询的哈希前缀。审计表永久保留、
     *       无 TTL（V34），把号码写进去等于建了第二份不会过期、且谁有 {@code admin.view_logs}
     *       都能翻的通讯录。哈希前缀足以回答「是不是同一个号被反复查」这个审计问题。</li>
     * </ol>
     *
     * <p>输入按 C-15 归一：{@code 08123…} / {@code 8123…} / {@code +62 812-3…} 都能命中同一批订单。
     */
    @Transactional
    public List<ShopOrder> searchByPhone(String rawPhone, Long actorAccountId, int limit) {
        String digits = digitsOf(rawPhone);
        if (digits.length() < MIN_PHONE_QUERY_DIGITS) {
            // 位数太少会把「搜索」变成「遍历」：4 位后缀能捞出全站近万分之一的订单。
            throw AppException.validation(
                    "按电话搜索至少需要 " + MIN_PHONE_QUERY_DIGITS + " 位数字")
                    .code("admin.err.shopOrder.phoneQueryTooShort", MIN_PHONE_QUERY_DIGITS);
        }
        List<ShopOrder> hits = orders.searchByPhoneSuffix("%" + digits,
                PageRequest.of(0, Math.max(1, limit)));
        audit.record(actorAccountId, AuditActions.SHOP_ORDER_SEARCHED_BY_PHONE, "SHOP_ORDER", null,
                "按电话搜索订单：命中 %d 条，查询指纹 %s".formatted(hits.size(), fingerprint(digits)));
        return hits;
    }

    /** 组合筛选（状态 + 时间范围）。不含电话 —— 那是独立能力。 */
    @Transactional(readOnly = true)
    public List<ShopOrder> search(ShopOrderStatus status, Instant from, Instant to, int limit) {
        return orders.search(status, from, to, PageRequest.of(0, Math.max(1, limit)));
    }

    /**
     * 一页订单（V1.3.0 Story 10.2 AC1，每页 20，下单时间倒序）。
     *
     * <p>⚠️ 曾经想用「多取一条判 hasNext」省掉 count —— 那是<b>错的</b>：
     * {@code PageRequest} 的偏移量是 {@code pageNumber × pageSize}，把 pageSize 写成 {@code size + 1}
     * 会让第 1 页从第 21 条开始，全局第 21 条永远不出现在任何一页（复审实测）。
     * 老实走 {@code Page.hasNext()}。
     */
    @Transactional(readOnly = true)
    public Page page(ShopOrderStatus status, Instant from, Instant to, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.max(1, size);
        var found = orders.searchPage(status, from, to, PageRequest.of(p, s));
        return new Page(found.getContent(), p, found.hasNext());
    }

    /** 一页订单。 */
    public record Page(List<ShopOrder> rows, int page, boolean hasNext) {
    }

    /**
     * 列表摘要条：待发货 · 在途 · 今日签收（V1.3.0 Story 10.2 AC1）。
     *
     * <p>AC 原文要求<b>单条 {@code COUNT FILTER} 聚合</b> —— 三个数一次往返，
     * 而不是三条 count 各扫一遍。走 {@code NamedParameterJdbcTemplate} 而不是 JPQL：
     * {@code FILTER (WHERE …)} 是 Postgres 的聚合过滤语法，JPQL 没有对应写法。
     *
     * <p>⚠️ 可空的时间范围参数<b>必须显式给 SQL 类型</b>（{@code Types.TIMESTAMP}，与
     * {@code AdminContentManageService} 的绑定口径一致）—— 本仓库踩过
     * 「{@code :param IS NULL OR …} 里无类型 null 参数 Postgres 推断不出类型」这个坑。
     *
     * <p>「待发货」「在途」随时间范围筛选联动（AC1），<b>但「今日签收」只按 WIB 当日的
     * {@code delivered_at} 窗口算，不叠加下单时间范围</b>：叠加的话，运营选「今天」这个最常用的区间时，
     * 它就只数「今天下单且今天签收」的单 —— 而前几天下单、今天签收的才是签收的主体，
     * 那个数会小得离谱且没人看得出为什么。「今日签收」按定义就是一个当天口径的数。
     */
    @Transactional(readOnly = true)
    public Summary summary(Instant from, Instant to) {
        java.time.ZonedDateTime dayStart = java.time.LocalDate.now(WIB).atStartOfDay(WIB);
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("from", from == null ? null : java.sql.Timestamp.from(from),
                        java.sql.Types.TIMESTAMP)
                .addValue("to", to == null ? null : java.sql.Timestamp.from(to),
                        java.sql.Types.TIMESTAMP)
                .addValue("dayStart", java.sql.Timestamp.from(dayStart.toInstant()),
                        java.sql.Types.TIMESTAMP)
                .addValue("dayEnd", java.sql.Timestamp.from(dayStart.plusDays(1).toInstant()),
                        java.sql.Types.TIMESTAMP);
        return jdbc.query(SUMMARY_SQL, params, rs -> rs.next()
                ? new Summary(rs.getLong("pending_shipment"), rs.getLong("in_transit"),
                        rs.getLong("delivered_today"))
                : new Summary(0, 0, 0));
    }

    private static final java.time.ZoneId WIB = java.time.ZoneId.of("Asia/Jakarta");

    private static final String SUMMARY_SQL = """
            SELECT count(*) FILTER (WHERE status = 'PENDING_SHIPMENT' AND in_range) AS pending_shipment,
                   count(*) FILTER (WHERE status = 'SHIPPED'          AND in_range) AS in_transit,
                   -- 「今日签收」刻意**不带 in_range**：见方法注释
                   count(*) FILTER (WHERE status = 'DELIVERED'
                                      AND delivered_at >= :dayStart
                                      AND delivered_at <  :dayEnd)              AS delivered_today
              FROM (
                    SELECT status, delivered_at,
                           (CAST(:from AS timestamptz) IS NULL OR created_at >= :from)
                       AND (CAST(:to   AS timestamptz) IS NULL OR created_at <  :to) AS in_range
                      FROM shop_orders
                   ) t
            """;

    /** 摘要条三格。 */
    public record Summary(long pendingShipment, long inTransit, long deliveredToday) {
    }

    /** 至少 6 位才允许搜 —— 再短就不是搜索而是遍历。 */
    static final int MIN_PHONE_QUERY_DIGITS = 6;

    /** 归一到「无区号、无前导 0」的数字串（C-15 三种输入形式指向同一结果）。 */
    private static String digitsOf(String raw) {
        String d = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (d.startsWith("62")) {
            d = d.substring(2);
        }
        while (d.startsWith("0")) {
            d = d.substring(1);
        }
        return d;
    }

    /**
     * 🔒 查询指纹：SHA-256 前 8 位十六进制。
     *
     * <p>可用于「同一个号是否被反复查」的审计判断，但<b>不可逆推号码</b>。
     */
    private static String fingerprint(String digits) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(digits.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /**
     * 发货推送（复用 FR-38 深链，直跳订单详情）。
     *
     * <p>🔒 <b>文案只说「订单已发货」</b> —— 不带商品名、不带收件人、不带地址。
     * 推送会落在锁屏上，旁人看得见。
     *
     * <p>🔴 <b>targetRef 用不可枚举的订单 token</b>，不用自增 id（CLAUDE.md 护栏）。
     */
    private void notifyShipped(ShopOrder order) {
        try {
            notifications.send(order.getUserId(), NotificationType.SHOP_ORDER_SHIPPED,
                    "订单已发货", "包裹已寄出，点击查看物流单号",
                    NotificationType.SHOP_ORDER_SHIPPED.name(), order.getPublicToken());
        } catch (RuntimeException e) {
            // 🔴 推送失败不得回滚发货：货已经交给承运商了，回滚只会让库里的状态与现实脱节。
            //    用户在订单详情里照样能看到单号 —— 通知是加速器，不是唯一通道。
            log.warn("发货通知发送失败（不影响发货）token={} cause={}", order.getPublicToken(),
                    e.getClass().getSimpleName());
        }
    }
}
