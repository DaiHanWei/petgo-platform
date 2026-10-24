package com.tailtopia.merchant.po.internal;

import com.tailtopia.merchant.po.domain.PoShippingMethod;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.merchant.po.internal.InternalPoDtos.AddressDto;
import com.tailtopia.merchant.po.internal.InternalPoDtos.CreateRequest;
import com.tailtopia.merchant.po.internal.InternalPoDtos.LineRequest;
import com.tailtopia.merchant.po.internal.InternalPoDtos.PageDto;
import com.tailtopia.merchant.po.internal.InternalPoDtos.PaymentDto;
import com.tailtopia.merchant.po.internal.InternalPoDtos.PurchaseOrderDto;
import com.tailtopia.merchant.po.internal.InternalPoDtos.SummaryDto;
import com.tailtopia.merchant.po.service.MerchantApiException;
import com.tailtopia.merchant.po.service.NewPurchaseOrder;
import com.tailtopia.merchant.po.service.PurchaseOrderPaymentService;
import com.tailtopia.merchant.po.service.PurchaseOrderService;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进货内部接口（契约第 5 节）。只在独立内网端口上可达，密钥与来源由 {@link InternalApiGuardFilter} 先挡。
 *
 * <p>门店身份 {@code X-Merchant-Store-Id}：缺失或不是正整数 → 400；本平台不存在该门店 → 404。
 * 错误格式见 {@link InternalMerchantProblemAdvice}。
 *
 * <p>路由：{@code summary}、{@code by-idempotency-key} 是固定段，Spring 的字面路径优先于 {@code {poToken}} 模板。
 */
@RestController
@RequestMapping("/internal/merchant/purchase-orders")
public class InternalMerchantPurchaseOrderController {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[0-9A-Za-z]{22}$");
    private static final Pattern KODE_POS = Pattern.compile("^[0-9]{5}$");
    private static final int MAX_LINES = 100;

    private final PurchaseOrderService orders;
    private final PurchaseOrderPaymentService payments;

    public InternalMerchantPurchaseOrderController(PurchaseOrderService orders,
            PurchaseOrderPaymentService payments) {
        this.orders = orders;
        this.payments = payments;
    }

    /** 接口 1：建单。新建 201，同 key 重放 200。 */
    @PostMapping
    public ResponseEntity<PurchaseOrderDto> create(
            @RequestHeader(value = "X-Merchant-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CreateRequest body) {
        long storeId = store(storeHeader);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw invalid("Idempotency-Key", "must be 22 base62 characters");
        }
        var result = orders.create(storeId, idempotencyKey, toCommand(body));
        return ResponseEntity.status(result.created() ? 201 : 200).body(PurchaseOrderDto.of(result.view()));
    }

    /** 接口 2：发起 QRIS 支付（天然幂等）。 */
    @PostMapping("/{poToken}/pay")
    public PaymentDto pay(@RequestHeader(value = "X-Merchant-Store-Id", required = false) String storeHeader,
            @PathVariable String poToken) {
        return PaymentDto.of(payments.pay(store(storeHeader), poToken));
    }

    /** 接口 5：已付款汇总。 */
    @GetMapping("/summary")
    public SummaryDto summary(@RequestHeader(value = "X-Merchant-Store-Id", required = false) String storeHeader) {
        var s = orders.summary(store(storeHeader));
        return new SummaryDto(s.paidCount(), s.paidGoodsAmount());
    }

    /** 接口 6：按幂等键查单（无副作用）。 */
    @GetMapping("/by-idempotency-key/{key}")
    public PurchaseOrderDto byIdempotencyKey(
            @RequestHeader(value = "X-Merchant-Store-Id", required = false) String storeHeader,
            @PathVariable String key) {
        long storeId = store(storeHeader);
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw invalid("key", "must be 22 base62 characters");
        }
        return PurchaseOrderDto.of(orders.getByIdempotencyKey(storeId, key));
    }

    /** 接口 3：单个进货单。 */
    @GetMapping("/{poToken}")
    public PurchaseOrderDto get(@RequestHeader(value = "X-Merchant-Store-Id", required = false) String storeHeader,
            @PathVariable String poToken) {
        return PurchaseOrderDto.of(orders.get(store(storeHeader), poToken));
    }

    /** 接口 4：按门店分页列表。越界页返回 200 + 空 items。 */
    @GetMapping
    public PageDto list(@RequestHeader(value = "X-Merchant-Store-Id", required = false) String storeHeader,
            @RequestParam(value = "page", required = false) String page,
            @RequestParam(value = "size", required = false) String size,
            @RequestParam(value = "status", required = false) String status) {
        long storeId = store(storeHeader);
        int p = intParam("page", page, 0);
        int s = intParam("size", size, 20);
        if (p < 0) {
            throw invalid("page", "must be >= 0");
        }
        if (s < 1 || s > 100) {
            throw invalid("size", "must be 1..100");
        }
        PurchaseOrderStatus st = null;
        if (status != null) {
            try {
                st = PurchaseOrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw invalid("status", "unknown status");
            }
        }
        return PageDto.of(orders.list(storeId, st, p, s));
    }

    // ---------- 门店身份 ----------

    private long store(String header) {
        long id;
        try {
            id = header == null ? 0 : Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            id = 0;
        }
        if (id <= 0) {
            throw invalid("X-Merchant-Store-Id", "must be a positive integer");
        }
        orders.requireStoreExists(id);
        return id;
    }

    // ---------- 请求体校验（契约 5.4：字符串都不得为空白） ----------

    static NewPurchaseOrder toCommand(CreateRequest b) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (b == null) {
            throw invalid("body", "required");
        }
        List<NewPurchaseOrder.Line> lines = new ArrayList<>();
        if (b.lines() == null || b.lines().isEmpty() || b.lines().size() > MAX_LINES) {
            errors.add(err("lines", "must have 1..100 items"));
        } else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < b.lines().size(); i++) {
                LineRequest l = b.lines().get(i);
                String f = "lines[" + i + "]";
                if (l == null) {
                    errors.add(err(f, "required"));
                    continue;
                }
                if (blank(l.skuToken()) || l.skuToken().length() > 32) {
                    errors.add(err(f + ".sku_token", "required, <= 32"));
                } else if (!seen.add(l.skuToken())) {
                    errors.add(err(f + ".sku_token", "duplicate"));
                }
                if (l.qty() == null || l.qty() < 1 || l.qty() > 9999) {
                    errors.add(err(f + ".qty", "must be 1..9999"));
                }
                if (l.wholesalePrice() == null || l.wholesalePrice() < 0) {
                    errors.add(err(f + ".wholesale_price", "must be >= 0"));
                }
                if (l.skuToken() != null && l.qty() != null && l.wholesalePrice() != null) {
                    lines.add(new NewPurchaseOrder.Line(l.skuToken(), l.qty(), l.wholesalePrice()));
                }
            }
        }
        AddressDto a = b.shippingAddress();
        if (a == null) {
            errors.add(err("shipping_address", "required"));
        } else {
            text(errors, "shipping_address.receiver_name", a.receiverName(), 40);
            text(errors, "shipping_address.receiver_phone", a.receiverPhone(), 16);
            text(errors, "shipping_address.provinsi", a.provinsi(), 60);
            text(errors, "shipping_address.kota_kabupaten", a.kotaKabupaten(), 60);
            text(errors, "shipping_address.kecamatan", a.kecamatan(), 60);
            text(errors, "shipping_address.address_line", a.addressLine(), 120);
            if (a.kodePos() == null || !KODE_POS.matcher(a.kodePos()).matches()) {
                errors.add(err("shipping_address.kode_pos", "must be 5 digits"));
            }
        }
        PoShippingMethod method = null;
        if (b.shippingMethod() != null) {
            try {
                method = PoShippingMethod.valueOf(b.shippingMethod());
            } catch (IllegalArgumentException ignored) {
                // 落到下面
            }
        }
        if (method == null) {
            errors.add(err("shipping_method", "must be FREE or COD_SHIPPING"));
        }
        if (b.storeSnapshot() == null) {
            errors.add(err("store_snapshot", "required"));
        } else {
            text(errors, "store_snapshot.name", b.storeSnapshot().name(), 80);
            text(errors, "store_snapshot.phone", b.storeSnapshot().phone(), 16);
        }
        if (!errors.isEmpty()) {
            throw new MerchantApiException(400, MerchantApiException.INVALID_REQUEST, "Invalid request body",
                    Map.of("errors", errors));
        }
        return new NewPurchaseOrder(lines, new AddressSnapshot(a.receiverName(), a.receiverPhone(), a.provinsi(),
                a.kotaKabupaten(), a.kecamatan(), a.addressLine(), a.kodePos()), method,
                b.storeSnapshot().name(), b.storeSnapshot().phone());
    }

    private static void text(List<Map<String, String>> errors, String field, String v, int max) {
        if (blank(v) || v.length() > max) {
            errors.add(err(field, "required, not blank, <= " + max));
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static Map<String, String> err(String field, String reason) {
        return Map.of("field", field, "reason", reason);
    }

    private static int intParam(String name, String raw, int dflt) {
        if (raw == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw invalid(name, "must be an integer");
        }
    }

    private static MerchantApiException invalid(String field, String reason) {
        return new MerchantApiException(400, MerchantApiException.INVALID_REQUEST, "Invalid request",
                Map.of("errors", List.of(err(field, reason))));
    }
}
