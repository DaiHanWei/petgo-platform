package com.tailtopia.shop.order.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 迟到款重新下单核对清单（V1.4.2 shop-admin Story 7.2 · AD-11 · F3 / F4）。纯函数，单测钉住。
 *
 * <ul>
 *   <li>系统项（服务端算）：原券仍可用（AVAILABLE 且属本人；<b>已过期不算问题</b>，被占用 / 用掉 / 作废不行）、PawCoin 余额 ≥
 *       {@code reorderSettlement} 算出的实际需扣额、逐行库存（<b>至少一行有货</b>即可，O-4）；</li>
 *   <li>手勾项（客服勾）：用户已同意、收货地址仍有效；</li>
 *   <li>全部满足才可提交；提交时服务端重查，不信任页面勾选。</li>
 * </ul>
 */
public final class ReorderChecklist {

    private ReorderChecklist() {
    }

    /** 原券现状。{@code usable} = AVAILABLE 且属本人（不看有效期）。 */
    public record CouponState(String label, boolean usable, boolean expired) {
    }

    /** 订单行库存分配结果：{@code locks} = 能锁到（逐行按下单顺序、同 SKU 累计扣减可售）。 */
    public record LineStock(long lineId, long skuId, String productName, int qty, boolean locks) {
    }

    public record Result(List<CouponState> coupons, boolean couponsOk, long coinRequired, long coinBalance,
            boolean coinOk, List<LineStock> lines, boolean anyInStock, boolean consented, boolean addressValid) {

        public boolean systemOk() {
            return couponsOk && coinOk && anyInStock;
        }

        public boolean allOk() {
            return systemOk() && consented && addressValid;
        }

        public List<LineStock> shortLines() {
            return lines.stream().filter(l -> !l.locks()).toList();
        }

        public long inStockCount() {
            return lines.stream().filter(LineStock::locks).count();
        }
    }

    /** 行项输入（与 {@link LineStock} 同字段，{@code locks} 由 {@link #allocate} 算）。 */
    public record LineNeed(long lineId, long skuId, String productName, int qty) {
    }

    /**
     * 逐行分配可售（按下单顺序；同 SKU 多行共用一份可售，前面的行先拿）。一行要么全锁要么不锁（不拆件）。
     */
    public static List<LineStock> allocate(List<LineNeed> lines, Map<Long, Long> available) {
        Map<Long, Long> left = new HashMap<>(available);
        List<LineStock> out = new ArrayList<>(lines.size());
        for (LineNeed l : lines) {
            long have = left.getOrDefault(l.skuId(), 0L);
            boolean locks = l.qty() > 0 && have >= l.qty();
            if (locks) {
                left.put(l.skuId(), have - l.qty());
            }
            out.add(new LineStock(l.lineId(), l.skuId(), l.productName(), l.qty(), locks));
        }
        return out;
    }

    public static Result evaluate(List<CouponState> coupons, long coinRequired, long coinBalance,
            List<LineStock> lines, boolean consented, boolean addressValid) {
        boolean couponsOk = coupons.stream().allMatch(CouponState::usable);
        boolean coinOk = coinRequired <= 0 || coinBalance >= coinRequired;
        boolean anyInStock = lines.stream().anyMatch(LineStock::locks);
        return new Result(List.copyOf(coupons), couponsOk, Math.max(0, coinRequired), coinBalance, coinOk,
                List.copyOf(lines), anyInStock, consented, addressValid);
    }

    /** 新单缺货说明（{@code ShopOrder.flagShortage}，上限 200 字；列不下写「等 N 行」）。 */
    public static String shortageNote(List<LineStock> shortLines) {
        String head = "迟到款重新下单时缺货：";
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < shortLines.size(); i++) {
            String item = (i == 0 ? "" : "、") + shortLines.get(i).productName() + " ×" + shortLines.get(i).qty();
            String tail = "等 " + shortLines.size() + " 行";
            if (sb.length() + item.length() + (i < shortLines.size() - 1 ? tail.length() + 1 : 0) > 200) {
                sb.append(" ").append(tail);
                return sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
            }
            sb.append(item);
        }
        return sb.toString();
    }
}
