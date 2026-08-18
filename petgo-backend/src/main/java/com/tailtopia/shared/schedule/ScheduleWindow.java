package com.tailtopia.shared.schedule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 定时生效 / 失效的**唯一一份判定**（V1.1.6 · AD-9）。
 *
 * <h2>🛡 为什么放在 shared 而不是顶置模块里</h2>
 * 它有**四个消费方**：顶置坑位取数、用户标签取数、装饰标签取数、以及后台列表展示的三段状态。
 * 后两者属 Epic 5 与后台。若各写一遍，左闭右开的边界迟早会在某处被写成全闭，
 * 结果就是「App 上已失效、后台还显示生效中」——这类不一致没有任何报错，只能靠人工发现。
 *
 * <h2>🛡 区间是左闭右开 {@code [开始, 结束)}</h2>
 * 结束时刻**当秒即算已结束**。运营配「10:00 到 12:00」时的心智是「12:00 就没了」，
 * 而不是「12:00 那一秒还在」。
 *
 * <h2>⚠️ 数据库那一侧还有一份判定，二者必须给出同样答案</h2>
 * 取数过滤只能写在 SQL 的 WHERE 里（捞出来再在内存里筛会破坏分页与游标契约），
 * 所以物理上没法与这里共用同一行代码。这个缺口靠一条**集成测试**堵：
 * 拿一组边界时刻逐一对照 SQL 与本类的结论，见 {@code ContentPinWindowParityIntegrationTest}。
 * 谁哪天把某一侧改成全闭，那条测试立刻红。
 *
 * <h2>时区</h2>
 * 运营配置的墙上时间一律按 <b>WIB（{@code Asia/Jakarta}）</b> 解释，入库转 UTC 绝对时刻；
 * 上下线按该绝对时刻执行，<b>不按客户端本地时区二次换算</b>。
 * 已接受的代价：印尼横跨三个时区，WIB 08:00 上线的内容对最东边的用户是当地 10:00，
 * 本版本不做分区上线。
 */
public final class ScheduleWindow {

    /** 印尼西部时间。运营心智以雅加达时间为准。 */
    public static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private ScheduleWindow() {
    }

    /**
     * 当前是否生效中。区间 {@code [startAt, endAt)}：开始那一刻算生效，结束那一刻算已结束。
     */
    public static boolean isActiveAt(Instant now, Instant startAt, Instant endAt) {
        return !now.isBefore(startAt) && now.isBefore(endAt);
    }

    /** 三段判定。后台列表展示的「待生效 / 生效中 / 已结束」用的就是它。 */
    public static SchedulePhase phaseAt(Instant now, Instant startAt, Instant endAt) {
        if (now.isBefore(startAt)) {
            return SchedulePhase.PENDING;
        }
        return now.isBefore(endAt) ? SchedulePhase.ACTIVE : SchedulePhase.ENDED;
    }

    /**
     * 两个时间窗是否重叠。
     *
     * <p>同样按左闭右开：<b>首尾相接不算重叠</b> —— 一条 10:00–12:00、另一条 12:00–14:00
     * 是合法排期，两者不会同时生效。
     */
    public static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * 生效意义上的结束时刻 = 排期结束时刻与提前结束时刻中**较早的那个**。
     *
     * <p>提前结束（如顶置内容被下架）单列一个字段、<b>不覆盖运营配的结束时间</b> ——
     * 覆盖掉之后运营只会看到「这条 14:32 结束了」，无从知道是排期到点还是被下架带走的。
     */
    public static Instant effectiveEnd(Instant endAt, Instant terminatedAt) {
        if (terminatedAt == null) {
            return endAt;
        }
        return terminatedAt.isBefore(endAt) ? terminatedAt : endAt;
    }

    /** 运营输入的 WIB 墙上时间 → UTC 绝对时刻。 */
    public static Instant fromWib(LocalDateTime wallClock) {
        return wallClock.atZone(WIB).toInstant();
    }

    /** UTC 绝对时刻 → WIB 墙上时间（后台回显用）。 */
    public static LocalDateTime toWib(Instant instant) {
        return LocalDateTime.ofInstant(instant, WIB);
    }
}
