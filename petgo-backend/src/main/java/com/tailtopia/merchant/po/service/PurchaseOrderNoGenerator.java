package com.tailtopia.merchant.po.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 进货单可读单号 {@code PO-yyMMdd-XXXXXX}（≤ 32，契约 5.3 {@code order_no}）。日期段取建单时刻的 WIB 日期，
 * 随机段 6 位 Crockford Base32（不含 I L O U），同 {@code ShopOrderDisplayNoGenerator} —— 不用自增序号，
 * 免得门店从自己的单号推出平台当日进货单量。
 */
@Component
public class PurchaseOrderNoGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int SEGMENT_LENGTH = 6;
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyMMdd");
    static final int MAX_TRIES = 5;

    private final SecureRandom random = new SecureRandom();

    public String generate(Instant createdAt) {
        StringBuilder sb = new StringBuilder(SEGMENT_LENGTH);
        for (int i = 0; i < SEGMENT_LENGTH; i++) {
            sb.append(CROCKFORD[random.nextInt(CROCKFORD.length)]);
        }
        return "PO-" + createdAt.atZone(WIB).format(YMD) + "-" + sb;
    }

    /** 先查后插（唯一冲突会把当前事务打成 aborted，不能靠捕获异常重试）。 */
    public String generateUnique(Instant createdAt, Predicate<String> exists) {
        for (int i = 0; i < MAX_TRIES; i++) {
            String candidate = generate(createdAt);
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("进货单号连续 " + MAX_TRIES + " 次冲突，疑似随机源异常");
    }
}
