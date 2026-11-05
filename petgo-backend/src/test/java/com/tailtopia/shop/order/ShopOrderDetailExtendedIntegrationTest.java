package com.tailtopia.shop.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（v1.4.2 shop Story 6.2 AC3）：订单详情收口字段（{@code GET /api/v1/me/shop-orders/{token}}）。订单直接插库。
 *
 * <p>新结算下单后 {@code amounts / paymentMethods} 与订单头一致的端到端覆盖由既有下单 L1（{@code CheckoutPlaceIntegrationTest}）
 * 的订单头 + 本类的「直接插库」两段合起来证明（装配器只搬运订单头列）。
 */
class ShopOrderDetailExtendedIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private long insertOrder(String token, long uid, String status, String extraCols, String extraVals) {
        jdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at%s)
                VALUES (?, ?, ?, ?, 100000, 10000, 0, 110000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now()%s)
                """.formatted(extraCols, extraVals), token, "TOKO-IT-" + token, uid, status);
        return jdbc.queryForObject("SELECT id FROM shop_orders WHERE public_token = ?", Long.class, token);
    }

    private long seedSkuId() {
        String pToken = "dp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "ds" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g, package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', 50000, 1000, 20, 15, 10)""", sToken, pid);
        return jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
    }

    @Test
    @DisplayName("1.4.2 前格式的存量订单（直接插库、新列为空）→ 详情 200，amounts 默认值，新字段整键省略")
    void stockOrderDetail() throws Exception {
        long uid = newUser().getId();
        String token = "de" + SEQ.incrementAndGet();
        insertOrder(token, uid, "COMPLETED", "", "");
        mvc.perform(get("/api/v1/me/shop-orders/" + token).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amounts.goodsAmount").value(100000))
                .andExpect(jsonPath("$.amounts.payable").value(110000))
                .andExpect(jsonPath("$.amounts.discountCouponAmount").value(0))
                .andExpect(jsonPath("$.amounts.shippingDiscountSource").doesNotExist())
                .andExpect(jsonPath("$.cancelReason").doesNotExist())
                .andExpect(jsonPath("$.couponReturn").doesNotExist())
                .andExpect(jsonPath("$.paymentMethods").doesNotExist())
                .andExpect(jsonPath("$.cancelledLines.length()").value(0));
    }

    @Test
    @DisplayName("超时取消单：cancelReason=PAYMENT_TIMEOUT、币 RETURNED、QRIS UNPAID、cancelledAt 在；越权 404")
    void timeoutCancelledDetail() throws Exception {
        long uid = newUser().getId();
        String token = "dc" + SEQ.incrementAndGet();
        insertOrder(token, uid, "CANCELLED",
                ", pay_channel, coin_amount, cash_amount, checkout_source, expires_at, cancel_reason, cancelled_at",
                ", 'MIXED', 10000, 100000, 'CART', now() - interval '10 minutes', 'PAYMENT_TIMEOUT', now()");
        mvc.perform(get("/api/v1/me/shop-orders/" + token).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelReason").value("PAYMENT_TIMEOUT"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.paymentMethods.coinStatus").value("RETURNED"))
                .andExpect(jsonPath("$.paymentMethods.cashStatus").value("UNPAID"))
                .andExpect(jsonPath("$.paymentMethods.coinAmount").value(10000));
        long other = newUser().getId();
        mvc.perform(get("/api/v1/me/shop-orders/" + token).header("Authorization", userBearer(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("部分取消：cancelledLines 列出 refunded_qty>0 的行与该行 LINE 退款合计；refunds[] 不含收款账号")
    void partialCancelDetail() throws Exception {
        long uid = newUser().getId();
        String token = "dl" + SEQ.incrementAndGet();
        long orderId = insertOrder(token, uid, "PENDING_SHIPMENT", ", paid_at", ", now()");
        long sku = seedSkuId();
        jdbc.update("""
                INSERT INTO shop_order_lines (order_id, sku_id, product_name, spec_name, unit_price, qty, line_total,
                        return_policy, refunded_qty)
                VALUES (?, ?, 'A', '3 kg', 50000, 1, 50000, 'RETURNABLE', 0),
                       (?, ?, 'B', '3 kg', 50000, 1, 50000, 'RETURNABLE', 1)
                """, orderId, sku, orderId, sku);
        Long lineB = jdbc.queryForObject(
                "SELECT id FROM shop_order_lines WHERE order_id = ? AND product_name = 'B'", Long.class, orderId);
        jdbc.update("""
                INSERT INTO shop_order_refunds (public_token, order_id, scope, order_line_id, line_qty, reason,
                        goods_refund, total_refund, coin_refund, cash_refund, status)
                VALUES (?, ?, 'LINE', ?, 1, 'OUT_OF_STOCK', 50000, 50000, 0, 50000, 'AWAITING_DESTINATION')
                """, "rf" + SEQ.incrementAndGet(), orderId, lineB);
        mvc.perform(get("/api/v1/me/shop-orders/" + token).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledLines.length()").value(1))
                .andExpect(jsonPath("$.cancelledLines[0].lineIndex").value(1))
                .andExpect(jsonPath("$.cancelledLines[0].cancelledQty").value(1))
                .andExpect(jsonPath("$.cancelledLines[0].refundAmount").value(50000))
                .andExpect(jsonPath("$.refunds[0].canChooseDestination").value(true))
                .andExpect(jsonPath("$.refunds[0].payoutAccount").doesNotExist());
        mvc.perform(get("/api/v1/me/shop-orders").param("group", "pending_shipment")
                        .header("Authorization", userBearer(uid)))
                .andExpect(jsonPath("$.items[0].refundActionRequired").value(true));
    }
}
