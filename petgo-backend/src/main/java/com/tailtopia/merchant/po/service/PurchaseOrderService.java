package com.tailtopia.merchant.po.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderLine;
import com.tailtopia.merchant.po.domain.PurchaseOrderShipment;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.merchant.po.repository.PurchaseOrderIdempotencyStore;
import com.tailtopia.merchant.po.repository.PurchaseOrderLineRepository;
import com.tailtopia.merchant.po.repository.PurchaseOrderRepository;
import com.tailtopia.merchant.po.repository.PurchaseOrderShipmentRepository;
import com.tailtopia.merchant.store.MerchantStoreDirectory;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门店进货单：建单、查询、超时取消（契约 5.4～5.6，FR-11～13，AD-17 / AD-19）。
 *
 * <p>🔴 <b>门店隔离由本类负责</b>（契约 5.1）：单个进货单一律按 {@code (merchant_store_id, po_token)} 或
 * {@code (merchant_store_id, idempotency_key)} 查，不匹配 → {@code 404}，不区分「不存在」与「不是你的」；
 * 列表与汇总只统计该门店的单。商家平台信任接口返回的行、不做二次核对。
 *
 * <p>🔴 <b>读即惰性取消</b>（契约 5.5 末段）：{@code now ≥ payment_deadline} 的待付款单在接口 3、4、6 读取时
 * 当场取消并释放锁定，页面不会在截止后仍显示「待付款」。定时扫描只是兜底。
 */
@Service
public class PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final PurchaseOrderShipmentRepository shipments;
    private final PurchaseOrderIdempotencyStore idempotency;
    private final MerchantStoreDirectory stores;
    private final ShopSkuRepository skus;
    private final ShopProductRepository products;
    private final InventoryService inventory;
    private final ShopTokenGenerator tokens;
    private final PurchaseOrderNoGenerator orderNos;
    private final PaymentIntentService paymentIntents;

    public PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderLineRepository lines,
            PurchaseOrderShipmentRepository shipments, PurchaseOrderIdempotencyStore idempotency,
            MerchantStoreDirectory stores, ShopSkuRepository skus, ShopProductRepository products,
            InventoryService inventory, ShopTokenGenerator tokens, PurchaseOrderNoGenerator orderNos,
            PaymentIntentService paymentIntents) {
        this.orders = orders;
        this.lines = lines;
        this.shipments = shipments;
        this.idempotency = idempotency;
        this.stores = stores;
        this.skus = skus;
        this.products = products;
        this.inventory = inventory;
        this.tokens = tokens;
        this.orderNos = orderNos;
        this.paymentIntents = paymentIntents;
    }

    // ---------- 门店身份 ----------

    /** 门店身份头指向的门店必须存在于本平台（契约 5.1：不存在 → 404 not-found）。 */
    @Transactional(readOnly = true)
    public void requireStoreExists(long storeId) {
        if (stores.findById(storeId).isEmpty()) {
            throw new MerchantApiException(404, MerchantApiException.NOT_FOUND, "Store not found", null);
        }
    }

    /** 门店状态第二道检查（AD-19）：不是 {@code ACTIVE} → {@code 403 store-disabled}。 */
    void requireStoreActive(long storeId) {
        boolean active = stores.findById(storeId).map(MerchantStoreDirectory.MerchantStoreRef::isActive)
                .orElse(false);
        if (!active) {
            throw MerchantApiException.storeDisabled();
        }
    }

    // ---------- 接口 1：建单 ----------

    /**
     * 建单 + 锁库存（契约 5.4），全程同一事务。
     *
     * <p>处理顺序：幂等 → 门店状态 → 批发价复核（一次列出全部变化行）→ 库存（一次列出全部缺货行）→ 建单。
     * 被拒（门店 / 价格 / 库存）随事务回滚、不写幂等记录，同一 key 之后可以再试。
     */
    @Transactional
    public CreateResult create(long storeId, String idempotencyKey, NewPurchaseOrder req) {
        // 同门店同 key 串行：并发的第二个请求等第一个提交后再查记录，拿到原单而不是撞主键。
        idempotency.lock(storeId, idempotencyKey);
        String digest = PurchaseOrderRequestDigest.of(req);
        var prior = idempotency.find(storeId, idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(digest)) {
                throw new MerchantApiException(409, MerchantApiException.IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key reused with a different request body", null);
            }
            PurchaseOrder existing = orders.findById(prior.get().purchaseOrderId())
                    .orElseThrow(MerchantApiException::notFound);
            expireIfOverdue(existing, Instant.now());
            return new CreateResult(view(existing), false);
        }

        requireStoreActive(storeId);

        List<String> skuTokens = req.lines().stream().map(NewPurchaseOrder.Line::skuToken).toList();
        Map<String, ShopSku> skuByToken = skus.findByPublicTokenIn(skuTokens).stream()
                .collect(Collectors.toMap(ShopSku::getPublicToken, Function.identity()));
        Map<Long, ShopProduct> productById = products.findAllById(
                        skuByToken.values().stream().map(ShopSku::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(ShopProduct::getId, Function.identity()));

        // 批发价复核：SKU 不存在 / 商品未上架 / 无批发价 / 与请求不等 → price-changed，一次列全。
        List<ChangedSku> changed = new ArrayList<>();
        for (NewPurchaseOrder.Line l : req.lines()) {
            Long current = currentWholesalePrice(skuByToken.get(l.skuToken()), productById);
            if (current == null || current != l.wholesalePrice()) {
                changed.add(new ChangedSku(l.skuToken(), current));
            }
        }
        if (!changed.isEmpty()) {
            throw new MerchantApiException(409, MerchantApiException.PRICE_CHANGED,
                    "Wholesale price changed or SKU no longer available", Map.of("changed_skus", changed));
        }

        // 库存：可售 = actual − locked ≥ qty，不足一次列全。
        Map<Long, Long> available = inventory.availableBySkuId(
                req.lines().stream().map(l -> skuByToken.get(l.skuToken()).getId()).toList());
        List<String> outOfStock = req.lines().stream()
                .filter(l -> available.getOrDefault(skuByToken.get(l.skuToken()).getId(), 0L) < l.qty())
                .map(NewPurchaseOrder.Line::skuToken).toList();
        if (!outOfStock.isEmpty()) {
            throw outOfStock(outOfStock);
        }

        Instant now = Instant.now();
        long goodsAmount = req.lines().stream().mapToLong(l -> l.wholesalePrice() * l.qty()).sum();
        PurchaseOrder po = orders.save(PurchaseOrder.place(tokens.generate(),
                orderNos.generateUnique(now, orders::existsByOrderNo), storeId, req.shippingMethod(),
                goodsAmount, req.shippingAddress(), req.storeName(), req.storePhone(), now));
        int lineNo = 0;
        for (NewPurchaseOrder.Line l : req.lines()) {
            lineNo++;
            ShopSku sku = skuByToken.get(l.skuToken());
            ShopProduct product = productById.get(sku.getProductId());
            try {
                inventory.lock(sku.getId(), l.qty());
            } catch (AppException e) {
                // 上面的可售判定与这里的锁定之间被并发买走：同样按缺货回，让店主重新确认。
                throw outOfStock(List.of(l.skuToken()));
            }
            lines.save(PurchaseOrderLine.of(po.getId(), lineNo, sku.getId(), sku.getPublicToken(),
                    product.getName(), sku.getSpecName(), product.getMainImageKey(), l.wholesalePrice(), l.qty()));
        }
        idempotency.insert(storeId, idempotencyKey, digest, po.getId());
        log.info("进货单建单 po={} lines={}", po.getPublicToken(), req.lines().size());
        return new CreateResult(view(po), true);
    }

    private static Long currentWholesalePrice(ShopSku sku, Map<Long, ShopProduct> productById) {
        if (sku == null) {
            return null;
        }
        ShopProduct product = productById.get(sku.getProductId());
        if (product == null || !product.isActive()) {
            return null;
        }
        return sku.getWholesalePrice();
    }

    private static MerchantApiException outOfStock(List<String> skuTokens) {
        return new MerchantApiException(409, MerchantApiException.OUT_OF_STOCK, "Insufficient stock",
                Map.of("out_of_stock_skus", skuTokens));
    }

    // ---------- 接口 3 / 6：单个 ----------

    @Transactional
    public PurchaseOrderView get(long storeId, String poToken) {
        PurchaseOrder po = orders.findByPublicTokenAndMerchantStoreId(poToken, storeId)
                .orElseThrow(MerchantApiException::notFound);
        expireIfOverdue(po, Instant.now());
        return view(po);
    }

    /** 接口 6：按幂等键查单，<b>无副作用</b>（不建单、不锁库存；惰性取消属读取口径，见类注释）。 */
    @Transactional
    public PurchaseOrderView getByIdempotencyKey(long storeId, String idempotencyKey) {
        var record = idempotency.find(storeId, idempotencyKey).orElseThrow(MerchantApiException::notFound);
        PurchaseOrder po = orders.findById(record.purchaseOrderId()).orElseThrow(MerchantApiException::notFound);
        expireIfOverdue(po, Instant.now());
        return view(po);
    }

    // ---------- 接口 4：列表 ----------

    @Transactional
    public PageView list(long storeId, PurchaseOrderStatus status, int page, int size) {
        Instant now = Instant.now();
        for (PurchaseOrder overdue : orders.findOverdueForStore(storeId, now)) {
            expireIfOverdue(overdue, now);
        }
        PageRequest pr = PageRequest.of(page, size);
        Page<PurchaseOrder> p = status == null ? orders.pageByStore(storeId, pr)
                : orders.pageByStoreAndStatus(storeId, status, pr);
        List<Long> ids = p.getContent().stream().map(PurchaseOrder::getId).toList();
        Map<Long, PurchaseOrderLine> firstLines = ids.isEmpty() ? Map.of()
                : lines.findByPurchaseOrderIdInAndLineNo(ids, 1).stream()
                        .collect(Collectors.toMap(PurchaseOrderLine::getPurchaseOrderId, Function.identity()));
        Map<Long, Long> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : lines.countByPurchaseOrderIds(ids)) {
                counts.put((Long) row[0], (Long) row[1]);
            }
        }
        List<RowView> rows = p.getContent().stream()
                .map(o -> new RowView(o, firstLines.get(o.getId()), counts.getOrDefault(o.getId(), 0L)))
                .toList();
        return new PageView(rows, page, size, p.getTotalElements(), p.getTotalPages());
    }

    // ---------- 接口 5：汇总 ----------

    @Transactional(readOnly = true)
    public Summary summary(long storeId) {
        Object[] row = orders.summarize(storeId, PurchaseOrderStatus.PAID_STATES).get(0);
        return new Summary(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    // ---------- 超时取消 ----------

    /**
     * 惰性取消：已过付款截止的待付款单当场取消、释放锁定（同一事务）。
     *
     * @return 是否真的取消了
     */
    @Transactional
    public boolean expireIfOverdue(PurchaseOrder po, Instant now) {
        if (!po.isPaymentOverdueAt(now)) {
            return false;
        }
        for (PurchaseOrderLine l : lines.findByPurchaseOrderIdOrderByLineNoAsc(po.getId())) {
            inventory.release(l.getSkuId(), l.getQty());
        }
        po.cancelForTimeout(now);
        orders.save(po);
        // 支付意图一并作废：截止后不该还有一张能付的二维码挂在那里（意图 TTL 本就 = 截止时刻，这里是兜底）。
        if (po.getPaymentIntentToken() != null) {
            paymentIntents.failByToken(po.getPaymentIntentToken(), "TIMEOUT");
        }
        log.info("进货单超时取消 po={}", po.getPublicToken());
        return true;
    }

    /** 扫描兜底的单笔事务（调用方逐笔经代理调用，单笔失败不阻断其余）。 */
    @Transactional
    public boolean expireOneOverdue(long purchaseOrderId) {
        PurchaseOrder po = orders.findById(purchaseOrderId).orElse(null);
        return po != null && expireIfOverdue(po, Instant.now());
    }

    // ---------- 装配 ----------

    @Transactional(readOnly = true)
    public PurchaseOrderView view(PurchaseOrder po) {
        return new PurchaseOrderView(po, lines.findByPurchaseOrderIdOrderByLineNoAsc(po.getId()),
                shipments.findByPurchaseOrderIdOrderByShippedAtAscIdAsc(po.getId()));
    }

    /** 进货单 + 明细 + 包裹（契约 5.3 {@code PurchaseOrder}）。 */
    public record PurchaseOrderView(PurchaseOrder order, List<PurchaseOrderLine> lines,
            List<PurchaseOrderShipment> shipments) {
    }

    /** @param created {@code true} = 新建（201），{@code false} = 同 key 重放（200） */
    public record CreateResult(PurchaseOrderView view, boolean created) {
    }

    /** 列表行：第一行明细（{@code line_no = 1}）+ 行数。 */
    public record RowView(PurchaseOrder order, PurchaseOrderLine firstLine, long lineCount) {
    }

    public record PageView(List<RowView> items, int page, int size, long totalElements, int totalPages) {
    }

    public record Summary(long paidCount, long paidGoodsAmount) {
    }

    /**
     * {@code price-changed} 的 {@code changed_skus} 元素（契约 5.7）。
     *
     * <p>🔴 {@code wholesale_price} <b>必须显式给出</b>：显式 {@code null} 表示不再有批发价、不可进货。
     * 全局 {@code default-property-inclusion: non_null} 会把它吞掉，故这里单独 {@code ALWAYS}。
     */
    public record ChangedSku(
            @JsonProperty("sku_token") String skuToken,
            @JsonProperty("wholesale_price") @JsonInclude(JsonInclude.Include.ALWAYS) Long wholesalePrice) {
    }
}
