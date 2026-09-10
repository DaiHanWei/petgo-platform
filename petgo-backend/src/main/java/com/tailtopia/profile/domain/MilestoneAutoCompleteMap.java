package com.tailtopia.profile.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 「物种 × 自动事件 → 完整里程碑 code」显式映射表（V1.3.0 批次 A · Story 1.1 · AD-A4）。
 *
 * <p><b>本表取代「物种前缀 + 语义后缀」的拼接寻址。</b>旧寻址
 * （{@code prefixOf(petType) + "-" + suffix}）隐含一个从未成立的前提：三张清单同号位含义相同。
 * 实际是猫 31 / 狗 31 / <b>通用 16</b>，排列完全不同，于是同一个后缀在通用清单上指到别的节点或指空：
 *
 * <table border="1">
 *   <caption>改造前通用宠物（OTHER）的五处错位</caption>
 *   <tr><th>旧后缀</th><th>猫狗含义</th><th>通用清单实际含义</th><th>后果</th></tr>
 *   <tr><td>{@code M3} 录疫苗</td><td>C/D-M3 疫苗</td><td>G-M3 陪伴满 30 天</td><td>错点亮</td></tr>
 *   <tr><td>{@code M4} 录驱虫</td><td>C/D-M4 驱虫</td><td>G-M4 记录满 10 条</td><td>错点亮</td></tr>
 *   <tr><td>{@code S14} 首次被评论</td><td>C/D-S14</td><td>无 S14（在 G-S7）</td><td>静默失效</td></tr>
 *   <tr><td>{@code S15} 首次被点赞</td><td>C/D-S15</td><td>无 S15（在 G-S8）</td><td>静默失效</td></tr>
 *   <tr><td>{@code M10} 记录满 10 条</td><td>C/D-M10</td><td>无 M10（在 G-M4）</td><td>静默失效</td></tr>
 * </table>
 *
 * <p>⚠️ <b>新增自动事件时，三个物种都要在本表逐条写出来</b>，包括「该物种没有对应节点」这一情况
 * —— 写 {@code null} 是一个**显式声明**，不是遗漏。别再引入任何按后缀推导 code 的辅助方法：
 * 那正是本次要消除的东西。
 *
 * <p>{@code null} 与「code 在 roster 里查不到」是两回事：前者表示该物种清单本就无此节点（预期、静默），
 * 后者说明 roster 与目录走散（异常）。调用侧不必区分，两者都不完成任何里程碑。
 */
public final class MilestoneAutoCompleteMap {

    private MilestoneAutoCompleteMap() {
    }

    /** 事件 → （物种 → 完整 code）。值为 {@code null} 表示该物种清单无对应节点。 */
    private static final Map<MilestoneAutoEvent, Map<PetType, String>> TABLE = build();

    /**
     * 该物种在该事件下应点亮的完整 code；该物种无对应节点 → {@code null}。
     *
     * @throws IllegalStateException 事件未在表中登记（新增事件忘了补三行，编译期查不出来，这里当场炸）
     */
    public static String codeOf(PetType petType, MilestoneAutoEvent event) {
        Map<PetType, String> row = TABLE.get(event);
        if (row == null) {
            throw new IllegalStateException("里程碑自动事件未登记映射: " + event);
        }
        return row.get(petType);
    }

    private static Map<MilestoneAutoEvent, Map<PetType, String>> build() {
        Map<MilestoneAutoEvent, Map<PetType, String>> t = new EnumMap<>(MilestoneAutoEvent.class);
        //                                           猫        狗        通用（OTHER）
        put(t, MilestoneAutoEvent.PROFILE_CREATED, "C-S1", "D-S1", "G-S1");
        put(t, MilestoneAutoEvent.GROWTH_MOMENT_FIRST, "C-S2", "D-S2", "G-S2");
        put(t, MilestoneAutoEvent.CARD_SHARED, "C-S3", "D-S3", "G-S3");
        put(t, MilestoneAutoEvent.CONSULT_ARCHIVED, "C-S4", "D-S4", "G-S4");
        put(t, MilestoneAutoEvent.PLATFORM_POST, "C-S5", "D-S5", "G-S5");
        // 🔴 三处静默失效的修复点：通用清单把「被评论 / 被点赞」放在 S7 / S8，不是 S14 / S15。
        put(t, MilestoneAutoEvent.FIRST_COMMENT, "C-S14", "D-S14", "G-S7");
        put(t, MilestoneAutoEvent.FIRST_LIKE, "C-S15", "D-S15", "G-S8");
        // 🔴 记录满 10 条：通用清单在 G-M4，不是 M10（旧寻址指空，合法路径是死的）。
        put(t, MilestoneAutoEvent.GROWTH_MOMENT_10, "C-M10", "D-M10", "G-M4");
        // 满 30 条只有猫狗有（G-L5 不存在）。
        put(t, MilestoneAutoEvent.GROWTH_MOMENT_30, "C-L5", "D-L5", null);
        // 🔴 两处错点亮的修复点：通用宠物录疫苗点亮 G-M2「第一次健康检查 / 疫苗」（决策 A-1），
        //    而不是 G-M3「陪伴满 30 天」；驱虫 / 绝育在通用清单**没有对应节点**，不点亮任何条目。
        put(t, MilestoneAutoEvent.HEALTH_RECORD_VACCINE, "C-M3", "D-M3", "G-M2");
        put(t, MilestoneAutoEvent.HEALTH_RECORD_DEWORM, "C-M4", "D-M4", null);
        put(t, MilestoneAutoEvent.HEALTH_RECORD_NEUTER, "C-M9", "D-M9", null);
        // 真人问诊结束：猫狗 M5「第一次看兽医」。通用清单的对应节点是 G-M1，但它当前仍是打卡类，
        // 接入属 Story 1.2（本 story 只改寻址、不改触发源），故此处维持「通用不点亮」的既有行为。
        put(t, MilestoneAutoEvent.CONSULT_CLOSED, "C-M5", "D-M5", null);
        // 陪伴满 30 天：通用清单在 G-M3（定时扫描原有的 OTHER 特判是对的，在此固化下来）。
        put(t, MilestoneAutoEvent.COMPANION_30_DAYS, "C-M8", "D-M8", "G-M3");
        // L 级日期门控三条，三张清单位置恰好对齐。
        put(t, MilestoneAutoEvent.FIRST_BIRTHDAY, "C-L1", "D-L1", "G-L1");
        put(t, MilestoneAutoEvent.COMPANION_100_DAYS, "C-L2", "D-L2", "G-L2");
        put(t, MilestoneAutoEvent.COMPANION_365_DAYS, "C-L3", "D-L3", "G-L3");
        return Map.copyOf(t);
    }

    private static void put(Map<MilestoneAutoEvent, Map<PetType, String>> t, MilestoneAutoEvent event,
            String cat, String dog, String other) {
        Map<PetType, String> row = new EnumMap<>(PetType.class);
        // 刻意用 EnumMap 而非 Map.of：null 值是本表的合法取值（「该物种无此节点」的显式声明），
        // Map.of 不接受 null。
        row.put(PetType.CAT, cat);
        row.put(PetType.DOG, dog);
        row.put(PetType.OTHER, other);
        t.put(event, java.util.Collections.unmodifiableMap(row));
    }
}
