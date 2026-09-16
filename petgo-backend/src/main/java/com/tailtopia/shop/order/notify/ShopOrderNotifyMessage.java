package com.tailtopia.shop.order.notify;

import java.util.List;

/**
 * 汇总提醒的消息文本（Story 3-4 AC5 / SHOP-NFR-01）。纯函数，无依赖，L0 可测。
 *
 * <p>🔒🔒 <b>每笔订单只写三样：订单号、金额、件数。</b>
 * 收件人姓名 / 电话 / 地址 / 下单人昵称 / 邮箱 —— 一个都不许进来。
 * 这条消息会落进一个 Lark 群，群里的人可能远多于该看订单 PII 的人，
 * 而且 Lark 的聊天记录不受我们的留存策略管。
 *
 * <p>⚠️ <b>本类刻意只收 {@link Line} 这个三字段的值对象，不收 {@code ShopOrder} 实体</b>。
 * 收实体的话，往消息里加一句「收件人：{@code order.shipTo().receiverName()}」
 * 就只是一行代码的事 —— 而那一行没人会在 review 时觉得不对。
 * 签名本身就是护栏，{@code ShopOrderNotifyMessageTest} 有逐字段的断言钉住它。
 */
public final class ShopOrderNotifyMessage {

    private ShopOrderNotifyMessage() {
    }

    /**
     * 一笔订单在提醒里的全部内容。
     *
     * @param displayNo 订单展示号（Story 4-3 落地后即 {@code shop_orders.display_no}）
     * @param totalAmount 订单总额（IDR，最小币种单位）
     * @param itemCount 件数
     */
    public record Line(String displayNo, long totalAmount, int itemCount) {
    }

    /**
     * 渲染汇总文本。
     *
     * @param lines 本窗口内的订单，**不得为空**（空窗口不发消息是调用方的责任，见扫描器）
     */
    public static String render(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("🛒 新订单 ").append(lines.size()).append(" 笔\n");
        for (Line l : lines) {
            sb.append("· ").append(l.displayNo())
                    .append(" | Rp ").append(formatIdr(l.totalAmount()))
                    .append(" | ").append(l.itemCount()).append(" 件\n");
        }
        sb.append("请到后台处理发货。");
        return sb.toString();
    }

    /** 千分位（IDR 无小数）。与后台展示口径一致，纯格式化，不引任何依赖。 */
    private static String formatIdr(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append('.');
            }
            out.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + out;
    }
}
