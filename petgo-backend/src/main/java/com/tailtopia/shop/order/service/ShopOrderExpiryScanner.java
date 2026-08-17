package com.tailtopia.shop.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 电商订单的时间驱动兜底扫描（Story 3.8 支付窗超时 · Story 4.1 履约段自动推进）。
 *
 * <p>DB 状态机驱动（{@code @Scheduled}，🔴 <b>禁 MQ / 延迟队列</b>，enforcement 护栏）。
 * 🔴 <b>Story 4.1 复用本组件、不新建定时器</b>（NFR-1）—— 三段扫描都是「按时间推进订单状态」，
 * 拆成三个组件只会让「一共有几个定时任务在改订单」这个问题没人答得上来。
 *
 * <p>三段各自独立 try/catch、各自独立节奏：
 * <ol>
 *   <li><b>支付窗超时</b>（1min）：{@code PENDING_PAYMENT} 且 {@code expires_at < now}
 *       → 取消并<b>释放库存</b>。用户可见的正确性由<b>懒过期</b>承担（读详情 / 点支付时就地判定），
 *       本扫描是兜底 —— 无人查看的订单不该永远锁着库存。🔴 这一条比意图过期扫描重要得多：
 *       支付意图滞留 PENDING 只是脏数据，而订单滞留 PENDING_PAYMENT 会一直占着别人想买的货。</li>
 *   <li><b>自动置送达</b>（10min）：SPEC-2 出口③，发货起 M=7 日无任何标记则置 {@code DELIVERED}。</li>
 *   <li><b>自动确认收货</b>（10min）：送达起 7 日未确认则置 {@code COMPLETED}（FR-102）。</li>
 *   <li><b>退货寄回超时</b>（10min）：S-7，超 7 日未寄回则关闭申请并回退订单状态。</li>
 *   <li><b>复购粮量见底日扫</b>（24h）：FR-109 / AD-12。🔴 <b>日扫而非请求时实时算</b> ——
 *       实时算需跨订单历史 + 商品喂量 + 档案体重三方 join，不该放在首页请求路径上。</li>
 * </ol>
 *
 * <p>⚠️ 后两段是<b>日级</b>时限，用 1min 节奏扫是白烧 1440 倍的查询；10min 的延迟对 7 天的窗口
 * 毫无影响。两条查询都有对应的部分索引（{@code ix_shop_orders_shipped_at / _delivered_at}）。
 */
@Component
public class ShopOrderExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderExpiryScanner.class);

    private static final int BATCH_LIMIT = 200;

    private final ShopOrderPaymentService payments;
    private final ShopOrderFulfillmentService fulfillment;
    private final com.tailtopia.shop.returns.service.ReturnRequestService returnRequests;
    private final com.tailtopia.shop.repurchase.service.RepurchaseScanService repurchase;

    public ShopOrderExpiryScanner(ShopOrderPaymentService payments,
            ShopOrderFulfillmentService fulfillment,
            com.tailtopia.shop.returns.service.ReturnRequestService returnRequests,
            com.tailtopia.shop.repurchase.service.RepurchaseScanService repurchase) {
        this.payments = payments;
        this.fulfillment = fulfillment;
        this.returnRequests = returnRequests;
        this.repurchase = repurchase;
    }

    @Scheduled(fixedDelayString = "${petgo.shop.order-expiry-scan-ms:60000}")
    public void scan() {
        try {
            int cancelled = payments.cancelOverdue(BATCH_LIMIT);
            if (cancelled > 0) {
                log.info("电商订单支付超时取消并释放库存 count={}", cancelled);
            }
        } catch (RuntimeException e) {
            log.warn("电商订单超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 履约段自动推进（Story 4.1）。
     *
     * <p>🔴 <b>两段分开 try/catch</b>：自动置送达炸了不该连累自动完成 ——
     * 后者服务的是另一批订单，两者之间没有任何依赖。
     */
    @Scheduled(fixedDelayString = "${petgo.shop.fulfillment-scan-ms:600000}")
    public void scanFulfillment() {
        try {
            int delivered = fulfillment.autoDeliverOverdue(BATCH_LIMIT);
            if (delivered > 0) {
                log.info("电商订单发货超 M 日自动置送达 count={}", delivered);
            }
        } catch (RuntimeException e) {
            log.warn("自动置送达扫描失败 cause={}", e.getClass().getSimpleName());
        }
        try {
            int completed = fulfillment.autoCompleteOverdue(BATCH_LIMIT);
            if (completed > 0) {
                log.info("电商订单送达超 7 日自动确认收货 count={}", completed);
            }
        } catch (RuntimeException e) {
            log.warn("自动确认收货扫描失败 cause={}", e.getClass().getSimpleName());
        }
        try {
            int closed = returnRequests.closeOverdueShipbacks(BATCH_LIMIT);
            if (closed > 0) {
                log.info("退货申请超 7 日未寄回自动关闭 count={}", closed);
            }
        } catch (RuntimeException e) {
            log.warn("退货寄回超时扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 复购粮量见底日扫（Story 6.3，FR-109 / AD-12）。
     *
     * <p>🔴 <b>缺输入不触发是常态，不是异常</b>：按当前 DEP-6 状态（每日建议喂量数据未到位），
     * 本扫描<b>上线首日极可能一条记录都不产生</b> —— 那时它应当安静地跑完，
     * 而不是刷一屏 warn 让人以为坏了。
     */
    @Scheduled(fixedDelayString = "${petgo.shop.repurchase-scan-ms:86400000}")
    public void scanRepurchase() {
        try {
            repurchase.scan(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        } catch (RuntimeException e) {
            log.warn("复购粮量日扫失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
