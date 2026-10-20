package com.tailtopia.content.rank;

import com.tailtopia.content.species.ContentSpecies;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主物种配额按宠物数拆分（V1.4.0 multi-pet Story 3.2 · AD-11 · PRD AC17）—— 纯函数，无依赖。
 *
 * <pre>
 *   total   = Σ counts                       （counts 为空或 total = 0 → 空 Map）
 *   base[s] = floor(quota × counts[s] / total)
 *   left    = quota − Σ base                 （0 ≤ left &lt; 物种数）
 *   left 个余数槽各 +1，依次给：
 *     宠物数多的物种优先（PRD「余数向宠物数多的物种取整」）
 *     → 同数时小数部分大的优先
 *     → 仍同时按固定顺序 CAT, DOG, OTHER
 * </pre>
 *
 * <p>🛡 只用整数运算（先乘后除、小数部分用 {@code (quota × count) mod total} 比较），不用 {@code double} ——
 * 浮点舍入会让「和恒等于 quota」在边界上失守。结果与输入 Map 的迭代顺序无关。
 *
 * <p>⚠️ 这<b>不是</b>最大余数法：3 猫 2 狗分 4 槽，最大余数法给狗（2 / 2），PRD 口径给猫（3 / 1）。
 */
public final class SpeciesSlotAllocator {

    /** 余数同数同小数时的固定顺序。 */
    private static final List<String> FIXED_ORDER =
            List.of(ContentSpecies.CAT, ContentSpecies.DOG, ContentSpecies.OTHER);

    private SpeciesSlotAllocator() {
    }

    /**
     * @param quota  要拆的配额（主物种配额）；负数按 0
     * @param counts 物种 → 宠物数；宠物数 ≤ 0 的项忽略
     * @return 物种 → 槽位数（和 = quota）；无有效物种 → 空 Map
     */
    public static Map<String, Integer> allocate(int quota, Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        long q = Math.max(0, quota);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                entries.add(Map.entry(e.getKey(), e.getValue()));
                total += e.getValue();
            }
        }
        if (total == 0) {
            return Map.of();
        }
        final long t = total;
        // 余数槽的优先顺序：宠物数多 → 小数部分大 → 固定顺序（未知物种排在固定顺序之后、按名字）。
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                .thenComparing(Comparator.comparingLong(
                        (Map.Entry<String, Integer> e) -> (q * e.getValue()) % t).reversed())
                .thenComparingInt(e -> fixedRank(e.getKey()))
                .thenComparing(Map.Entry::getKey));

        Map<String, Integer> out = new LinkedHashMap<>();
        long assigned = 0;
        for (Map.Entry<String, Integer> e : entries) {
            long base = (q * e.getValue()) / t;
            out.put(e.getKey(), (int) base);
            assigned += base;
        }
        long left = q - assigned;
        for (int i = 0; i < left; i++) {
            out.merge(entries.get(i).getKey(), 1, Integer::sum);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static int fixedRank(String species) {
        int i = FIXED_ORDER.indexOf(species);
        return i < 0 ? FIXED_ORDER.size() : i;
    }
}
