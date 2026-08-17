package com.tailtopia.shop.repurchase.service;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.domain.DepletionForecast;
import com.tailtopia.shop.repurchase.domain.RepurchaseTrigger;
import com.tailtopia.shop.repurchase.domain.RepurchaseTriggerStatus;
import com.tailtopia.shop.repurchase.repository.RepurchaseTriggerRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 粮量见底日扫（Story 6.3，FR-109 / AD-12 / S-14）。
 *
 * <p>🔴 <b>日扫落库，不做请求时实时计算</b>（AD-12）：实时算需跨订单历史 + 商品喂量 + 档案体重
 * 三方 join，放在首页请求路径上不合适。首页与推送读的是<b>同一份</b>落库结果，口径天然一致。
 *
 * <p>🔴 <b>不新建提醒引擎</b>（FR-109 明写「复用现有推送通道」）：推送走既有
 * {@link NotificationService}，只在 {@code NotificationType} 末尾追加了一个值。
 *
 * <p>🔴 <b>缺输入是常态，不是异常</b>：无购买历史 / 商品未配日喂量 / 档案无体重 → 静默不触发、
 * 不报错、不做兜底猜测。按当前 <b>DEP-6</b> 状态，<b>上线首日极可能对全体用户不触发</b> ——
 * 全库无喂量数据时本扫描应当安静地跑完并产生 0 条记录。
 */
@Service
public class RepurchaseScanService {

    private static final Logger log = LoggerFactory.getLogger(RepurchaseScanService.class);

    /** 只看最近这么多天内送达的订单 —— 再早的粮早就吃完了，算它只是白烧 CPU。 */
    private static final int LOOKBACK_DAYS = 365;

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final PetProfileRepository profiles;
    private final RepurchaseTriggerRepository triggers;
    private final NotificationService notifications;

    public RepurchaseScanService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ShopSkuRepository skus, ShopProductRepository products, PetProfileRepository profiles,
            RepurchaseTriggerRepository triggers, NotificationService notifications) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.skus = skus;
        this.products = products;
        this.profiles = profiles;
        this.triggers = triggers;
        this.notifications = notifications;
    }

    /**
     * 跑一次日扫。
     *
     * @return 本次新建的触发记录数（DEP-6 未到位时正常为 0）
     */
    public int scan(LocalDate today) {
        int created = 0;
        for (ShopOrder order : orders.findDeliveredSince(
                today.minusDays(LOOKBACK_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant())) {
            try {
                created += scanOrder(order.getId(), today);
            } catch (RuntimeException e) {
                // 🔴 单笔失败不阻断整批 —— 一个坏订单不该让全站的补货提醒都停摆
                log.warn("复购日扫单笔失败 orderId={} cause={}", order.getId(),
                        e.getClass().getSimpleName());
            }
        }
        if (created > 0) {
            log.info("复购日扫生成触发记录 count={}", created);
        }
        return created;
    }

    /**
     * 扫一笔订单。
     *
     * <p>🔴 <b>每一处 {@code continue} 都是「静默不触发」</b>，不是防御性编程的顺手一写：
     * FR-109 明确要求缺输入时不猜、不报错。
     */
    @Transactional
    public int scanOrder(long orderId, LocalDate today) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.getDeliveredAt() == null) {
            return 0;   // 未签收 → 还没开始吃
        }
        PetProfile pet = profiles.findByOwnerId(order.getUserId()).orElse(null);
        if (pet == null || pet.getWeightKg() == null) {
            return 0;   // 🔴 档案无体重 → 静默（这是 DEP-6 之外最常见的缺输入）
        }
        // 🔴 修正②：以【送达日】起算，不是下单日
        LocalDate deliveredOn = order.getDeliveredAt().atZone(ZoneOffset.UTC).toLocalDate();

        int created = 0;
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(orderId)) {
            // 已全额退掉的行不该再算粮 —— 那些粮根本没到用户手上
            int effectiveQty = line.getQty() - line.getRefundedQty();
            if (effectiveQty <= 0) {
                continue;
            }
            ShopSku sku = skus.findById(line.getSkuId()).orElse(null);
            if (sku == null) {
                continue;
            }
            ShopProduct product = products.findById(sku.getProductId()).orElse(null);
            // 只有 Makanan（粮）参与粮量预估
            if (product == null || product.getCategory() != ProductCategory.MAKANAN) {
                continue;
            }
            // 🔴 商品未配日喂量（DEP-6 未到位）→ 静默。上线首日这是常态。
            LocalDate depletion = DepletionForecast.estimateDepletionDate(
                    product.getFeedingGuide(), pet.getWeightKg(), sku.getNetWeightG(),
                    effectiveQty, deliveredOn);
            if (depletion == null || !DepletionForecast.shouldTriggerOn(depletion, today)) {
                continue;
            }
            if (upsertTrigger(order, pet, sku, depletion)) {
                created++;
            }
        }
        return created;
    }

    /**
     * 落触发记录并推送一次。
     *
     * <p>🔴 <b>同一 (用户, SKU) 至多一条进行中</b>：日扫每天都跑，没有这条约束会每天多一行。
     * 与订单侧同一处置 —— 并发正确性交给<b>库级部分唯一索引</b>，本方法只把冲突翻译成「跳过」。
     *
     * <p>🔴 <b>推送一次</b>：已推过的触发在后续日扫里<b>不重推</b>。
     */
    private boolean upsertTrigger(ShopOrder order, PetProfile pet, ShopSku sku,
            LocalDate depletion) {
        var existing = triggers.findByUserIdAndSkuIdAndStatus(order.getUserId(), sku.getId(),
                RepurchaseTriggerStatus.ACTIVE);
        if (existing.isPresent()) {
            return false;   // 已有进行中的同 SKU 触发 → 不重复建、不重推
        }
        RepurchaseTrigger t = RepurchaseTrigger.foodLow(order.getUserId(), pet.getId(),
                sku.getId(), order.getId(), depletion);
        try {
            triggers.saveAndFlush(t);
        } catch (DataIntegrityViolationException e) {
            return false;   // 并发下被库级索引挡住 —— 那说明别的线程刚建过，正是我们要的结果
        }
        notifyOnce(t, pet);
        return true;
    }

    /**
     * 推送一次。
     *
     * <p>🔒 文案<b>只说「快吃完了」，不带体重、不带具体克数</b> ——
     * 体重是 PII 邻近的健康数据，而推送会落在锁屏上（NFR-5）。
     *
     * <p>🔴 文案<b>给估算依据而非断言</b>：说「预计还能吃 ~N 天」，不说「已经吃完了」。
     * 档案体重不准或用户混喂时会有偏差，把估算说成事实会直接损伤信任。
     */
    private void notifyOnce(RepurchaseTrigger t, PetProfile pet) {
        try {
            notifications.send(t.getUserId(), NotificationType.REPURCHASE_FOOD_LOW,
                    "补货提醒", "%s 的粮预计快吃完了".formatted(pet.getName()),
                    NotificationType.REPURCHASE_FOOD_LOW.name(), String.valueOf(t.getSkuId()));
            t.markNotified();
            triggers.save(t);
        } catch (RuntimeException e) {
            // 推送失败不回滚触发记录：卡片照样能在首页出现，推送只是加速器
            log.warn("补货提醒推送失败（不影响触发记录）userId={} cause={}", t.getUserId(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 🔴 <b>用户再次购买该 SKU → 旧触发立即失效，按新订单重新起算</b>（FR-109）。
     *
     * <p>由下单链路调用。不做这件事的话，用户明明刚买过还会一直看到「快没粮了」。
     */
    @Transactional
    public int supersedeOnRepurchase(long userId, List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (RepurchaseTrigger t : triggers.findByUserIdAndSkuIdIn(userId, skuIds)) {
            if (t.getStatus() == RepurchaseTriggerStatus.ACTIVE) {
                // 从触发卡点进来买的算转化（AB-13B 判定 A-16 的分子）——
                // 归因由订单行的 triggerType 承担，这里只负责让卡片消失。
                t.supersede();
                triggers.save(t);
                n++;
            }
        }
        return n;
    }

    /**
     * 首页区域① 读这个。⚠️ 排序规则 SPEC-16 未拍板，当前按耗尽日升序（越快没粮的越靠前）。
     *
     * <p>🔴 <b>读时就地失效</b>（与支付窗的懒过期同范式）：用户再次买过该 SKU 的触发
     * <b>立即</b>被标为 SUPERSEDED 并从结果里剔除。
     * 做成读时判定而不是在支付链路上挂钩子，是因为 Epic 3 的支付链路是资金与库存的
     * 同事务临界区 —— Epic 6 不该往里面塞自己的副作用。日扫是它的兜底。
     */
    @Transactional
    public List<RepurchaseTrigger> activeTriggersFor(long userId) {
        List<RepurchaseTrigger> active = triggers
                .findByUserIdAndStatusOrderByEstimatedDepletionDateAsc(userId,
                        RepurchaseTriggerStatus.ACTIVE);
        List<RepurchaseTrigger> live = new java.util.ArrayList<>();
        for (RepurchaseTrigger t : active) {
            if (orderLines.existsPaidLineForSkuAfter(userId, t.getSkuId(), t.getCreatedAt())) {
                t.supersede();
                triggers.save(t);
                continue;
            }
            live.add(t);
        }
        return live;
    }

    /** SKU → 商品名（首页卡片文案要用宠物名 + 商品名）。 */
    @Transactional(readOnly = true)
    public Map<Long, String> productNamesBySkuId(List<Long> skuIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Long skuId : skuIds) {
            skus.findById(skuId).flatMap(s -> products.findById(s.getProductId()))
                    .ifPresent(p -> out.put(skuId, p.getName()));
        }
        return out;
    }

    /** 状态过滤用（订单转已送达才参与）。 */
    static boolean isDelivered(ShopOrder o) {
        return o.getStatus() == ShopOrderStatus.DELIVERED
                || o.getStatus() == ShopOrderStatus.COMPLETED;
    }
}
