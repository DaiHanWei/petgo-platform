package com.tailtopia.shop.order.service;

import com.tailtopia.order.dto.OrderDisplayNo;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 电商订单展示号生成器（Story 4-3 · SHOP-FR-29 / SHOP-FR-30 · AD-S3）。
 *
 * <p>格式 {@code TOKO-yyyyMMdd-XXXXXX}：日期段取<b>下单时刻的 WIB 日期</b>，
 * {@code XXXXXX} 是 <b>Crockford Base32 的 6 位随机</b>，由 {@link SecureRandom} 产生。
 *
 * <p>🔴 <b>它替代的是一个可枚举的号。</b>旧算法（{@code OrderDisplayNo.of}）的序号段就是
 * {@code shop_orders.id} 零填充 6 位 —— 任何用户拿自己的订单号即可推断平台当日单量与累计单量，
 * 还能顺着序号试探别人的单。这正是 CLAUDE.md 那条「对外暴露标识一律不可枚举」要挡的事。
 *
 * <p>🔴 <b>前缀直接引用 {@link OrderDisplayNo#ECOMMERCE}，不在本类重写字面量 {@code "TOKO"}</b>：
 * 两份字面量迟早分叉。前缀保留的理由也没变 —— 财务要能一眼区分自营实物与虚拟商品收入。
 * （⚠️ 核实过：对账 SQL {@code ShopFinanceDashboardService} 是<b>按表圈定</b>、不解析前缀，
 * 所以前缀服务的是<b>人眼识别</b>与既有测试断言，不是一条会崩的代码路径。
 * 这条事实写在这里，免得有人以为「反正没代码依赖」就顺手改了。）
 *
 * <p>Crockford 字母表 {@code 0123456789ABCDEFGHJKMNPQRSTVWXYZ} 共 32 字符，
 * <b>刻意不含 {@code I} {@code L} {@code O} {@code U}</b>（{@code I}/{@code 1}、{@code O}/{@code 0}
 * 易混，{@code U} 避免拼出脏词）—— 用户要把这个号<b>逐位念给客服</b>，认错一位就是查错单。
 * 6 位 ≈ 10.7 亿种组合，而唯一性只需在<b>同一天</b>内成立。
 */
@Component
public class ShopOrderDisplayNoGenerator {

    /** Crockford Base32。🔴 顺序与内容都不能改 —— 它决定了号里不会出现易混字符。 */
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final int SEGMENT_LENGTH = 6;

    /** 🔴 与 {@code OrderDisplayNo} 同一个时区：日期段的含义必须全仓一致。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 冲突重试上限。用尽即抛 —— 死循环比一次失败的下单难查得多。 */
    static final int MAX_TRIES = 5;

    private final SecureRandom random = new SecureRandom();

    /** 生成一个候选号（不查重）。 */
    public String generate(Instant createdAt) {
        StringBuilder sb = new StringBuilder(SEGMENT_LENGTH);
        for (int i = 0; i < SEGMENT_LENGTH; i++) {
            sb.append(CROCKFORD[random.nextInt(CROCKFORD.length)]);
        }
        return OrderDisplayNo.ECOMMERCE + "-" + createdAt.atZone(WIB).format(YMD) + "-" + sb;
    }

    /**
     * 生成一个库里还没有的号。
     *
     * <p>🔴 <b>重试靠「先查 {@code exists} 再插」，绝不靠捕获唯一约束异常。</b>
     * 在 JPA/Postgres 里，唯一约束冲突会把<b>当前事务整个打成 aborted 状态</b> ——
     * 同一事务内接着重试，每一次都会撞在「事务已中止」上而不是撞在号上，
     * 表现为「重试了 5 次、5 次都失败、日志里还看不出真正原因」。
     * 唯一索引是<b>兜底报错</b>（防并发穿越），不是重试机制。
     *
     * @param exists 判定该号是否已存在（生产传 {@code orders::existsByDisplayNo}）
     * @throws IllegalStateException 连续 {@value #MAX_TRIES} 次冲突
     */
    public String generateUnique(Instant createdAt, Predicate<String> exists) {
        for (int i = 0; i < MAX_TRIES; i++) {
            String candidate = generate(createdAt);
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        // 🔒 不把候选号写进异常信息：它会进日志，而日志里不放订单标识。
        //    6 位 Crockford 连撞 5 次意味着当天号段异常密集或随机源出了问题，
        //    这两种都需要人看一眼，不该静默重试下去。
        throw new IllegalStateException(
                "电商订单展示号连续 " + MAX_TRIES + " 次冲突，疑似随机源异常");
    }
}
