package com.tailtopia.admin.dailyreport;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日报 → 飞书自定义机器人 {@code interactive} 卡片（纯函数，便于单测）。
 *
 * <p>结构：header（标题带日期）→ 每个模块一段 {@code div + fields}，模块之间 {@code hr} 分隔
 * → 末尾 {@code note} 写统计口径。每个指标一格：「名称 / 数值 / 环比」。
 */
public final class DailyReportCard {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    static final String NOTE = "统计周期：昨日 00:00–24:00（WIB）｜环比对比前一天，分母为 0 显示「—」｜"
            + "已排除虚拟账号与管理员｜付费只计现金（QRIS 及混合支付现金段），不含 PawCoin｜"
            + "电商订单 = 当日下单且已付款，GMV 含 PawCoin 抵扣｜活跃率 = 评论数或 like 数 ÷ 日活";

    private DailyReportCard() {
    }

    public static Map<String, Object> build(DailyReport r) {
        DailyReport.Metrics c = r.current();
        DailyReport.Metrics p = r.previous();

        List<Object> elements = new ArrayList<>();
        section(elements, "👥 用户", List.of(
                field("昨日新增", count(c.newUsers()), DailyReport.changePct(c.newUsers(), p.newUsers())),
                field("昨日日活", c.dau() == null ? "—" : count(c.dau()), DailyReport.changePct(c.dau(), p.dau()))));
        elements.add(hr());
        section(elements, "💬 社区", List.of(
                field("新增帖子", count(c.newPosts()), DailyReport.changePct(c.newPosts(), p.newPosts())),
                field("评论数", count(c.comments()), DailyReport.changePct(c.comments(), p.comments())),
                field("like 数", count(c.likes()), DailyReport.changePct(c.likes(), p.likes())),
                field("评论活跃率", rate(c.commentRate()), DailyReport.changePct(c.commentRate(), p.commentRate())),
                field("like 活跃率", rate(c.likeRate()), DailyReport.changePct(c.likeRate(), p.likeRate()))));
        elements.add(hr());
        section(elements, "💰 付费", List.of(
                field("付费订单数", count(c.paidOrders()), DailyReport.changePct(c.paidOrders(), p.paidOrders())),
                field("付费用户数", count(c.payingUsers()), DailyReport.changePct(c.payingUsers(), p.payingUsers())),
                field("付费金额", idr(c.paidAmount()), DailyReport.changePct(c.paidAmount(), p.paidAmount()))));
        elements.add(hr());
        section(elements, "🛒 电商", List.of(
                field("电商订单数", count(c.shopOrders()), DailyReport.changePct(c.shopOrders(), p.shopOrders())),
                field("GMV", idr(c.gmv()), DailyReport.changePct(c.gmv(), p.gmv()))));
        elements.add(Map.of("tag", "note",
                "elements", List.of(Map.of("tag", "plain_text", "content", NOTE))));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", Map.of(
                "template", "purple",
                "title", Map.of("tag", "plain_text", "content", "TailTopia 日报 · " + DATE.format(r.date()))));
        card.put("elements", elements);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);
        return payload;
    }

    private static void section(List<Object> elements, String title, List<Map<String, Object>> fields) {
        elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**" + title + "**")));
        elements.add(Map.of("tag", "div", "fields", fields));
    }

    private static Map<String, Object> field(String label, String value, Double change) {
        return Map.of("is_short", true, "text", Map.of("tag", "lark_md",
                "content", "**" + label + "**\n" + value + "　" + change(change)));
    }

    private static Map<String, Object> hr() {
        return Map.of("tag", "hr");
    }

    /** 环比展示：↑ +12.3% / ↓ -5.0% / 持平 0.0% / 无法计算「—」。 */
    static String change(Double pct) {
        if (pct == null) {
            return "<font color='grey'>环比 —</font>";
        }
        String num = String.format(Locale.ROOT, "%+.1f%%", pct);
        if (pct > 0) {
            return "<font color='green'>↑ " + num + "</font>";
        }
        if (pct < 0) {
            return "<font color='red'>↓ " + num + "</font>";
        }
        return "<font color='grey'>持平 " + num + "</font>";
    }

    /** 千分位用印尼习惯的「.」（与 App 内金额展示一致）。 */
    static String count(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', '.');
    }

    static String idr(long v) {
        return "Rp " + count(v);
    }

    static String rate(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
