package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditFamily;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 到期提醒分组（V1.5.0 gift-pack Story 4-4 · AC4.2 · D-27）：纯函数，无 IO。
 *
 * <p>按（用户, 大类）分组；每组取最早到期的 WIB 日期 {@code D}，{@code n} = 该组 {@code expires_at} 落在 WIB 日 {@code D}
 * 的批剩余之和；本组被扫到的批（窗口内全部）都要置 {@code expiry_notified_at}（每批只提醒一次）。
 */
public final class EntitlementExpiryGrouping {

    public static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private EntitlementExpiryGrouping() {
    }

    /** 窗口内的一批（未用完、未过期、未提醒）。 */
    public record Batch(long creditId, long userId, CreditFamily family, int remaining, Instant expiresAt) {
    }

    /**
     * 一条提醒。
     *
     * @param date      最早到期的 WIB 日期
     * @param count     该日到期的剩余合计
     * @param creditIds 本组被扫到的全部批（置 notified）
     */
    public record Group(long userId, CreditFamily family, LocalDate date, int count, List<Long> creditIds) {
    }

    public static LocalDate wibDate(Instant at) {
        return at.atZone(WIB).toLocalDate();
    }

    /** 分组；输出按 (userId, family 声明序) 排序。 */
    public static List<Group> group(List<Batch> batches) {
        Map<String, List<Batch>> byKey = new LinkedHashMap<>();
        batches.stream()
                .sorted(Comparator.comparingLong(Batch::userId).thenComparing(Batch::family))
                .forEach(b -> byKey.computeIfAbsent(b.userId() + ":" + b.family().name(), k -> new ArrayList<>())
                        .add(b));
        List<Group> out = new ArrayList<>();
        for (List<Batch> g : byKey.values()) {
            LocalDate earliest = g.stream().map(b -> wibDate(b.expiresAt())).min(Comparator.naturalOrder())
                    .orElseThrow();
            int n = g.stream().filter(b -> wibDate(b.expiresAt()).equals(earliest)).mapToInt(Batch::remaining).sum();
            out.add(new Group(g.get(0).userId(), g.get(0).family(), earliest, n,
                    g.stream().map(Batch::creditId).toList()));
        }
        return List.copyOf(out);
    }
}
