package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.HealthMilestones;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import java.util.Objects;

/**
 * 里程碑达成路径 → 埋点 {@code path} 属性（V1.1.2 Story 6.1 · T-12）。**纯函数，L0 可测。**
 *
 * <p>为什么需要这一层映射：落库的 {@link MilestoneCompletionSource} 只有三个值
 * （SYSTEM_AUTO / USER_CHECKIN / PUBLISH），而产品要区分的是「**由什么**点亮的」——
 * 同一个 SYSTEM_AUTO 既可能来自健康记录，也可能来自兽医问诊结束，也可能只是计数到阈值。
 * 这两个口径不同，所以不能直接把 source 名字当 path 用。
 *
 * <p>判定顺序（互斥且穷尽）：
 * <ol>
 *   <li>USER_CHECKIN → {@code checkin}</li>
 *   <li>PUBLISH → {@code publish}</li>
 *   <li>SYSTEM_AUTO + 问诊点亮的 code（C-M5 / D-M5 / G-M1，第一次看兽医）→ {@code consult}；
 *       其余健康类（M3 疫苗 / M4 驱虫 / M9 绝育）→ {@code health_record}</li>
 *   <li>其它 SYSTEM_AUTO → {@code system_auto}</li>
 * </ol>
 *
 * <p><b>AC5 的线上校验就靠这里</b>：健康类四条已在 Story 5.2 取消打卡路径、且后端在写库前
 * 直接拒绝。因此线上若出现「健康类 code + {@code path=checkin}」，说明那道护栏被绕过了 ——
 * 这是一个可以配成告警的信号。注意本类**不做拒绝**（拒绝是 {@code MilestoneCheckInService}
 * 的职责）：如果这里把它改写成别的 path，就会把护栏失效现场擦掉，反而看不见问题。
 */
public final class MilestoneAnalyticsPath {

    /**
     * 由**真人兽医问诊结束**点亮的节点，按**完整 code** 显式列举（V1.3.0 Story 1.1 · AD-A4）。
     *
     * <p>🔴 原实现按后缀 {@code M5} 判。猫狗清单上「第一次看兽医」确实是 M5，但**通用清单把它放在
     * G-M1** —— 按后缀判，G-M1 永远命中不了 consult 分支；等它进了健康类集合（Story 1.2），
     * 就会落进 else 被标成 {@code health_record}，而它根本不是由健康记录点亮的。埋点口径一错，
     * AD-A4 那条「健康类 + checkin 即护栏失效」的告警也跟着失真。
     *
     * <p>G-M1 现在就写进来：它此刻还是打卡类、走不到 SYSTEM_AUTO 分支，写在这里不改变任何当下行为，
     * 但 Story 1.2 接上触发源的那一刻口径就是对的，不依赖后来人记得回头补这一处。
     */
    private static final java.util.Set<String> CONSULT_CODES =
            java.util.Set.of("C-M5", "D-M5", "G-M1");

    private MilestoneAnalyticsPath() {
    }

    /**
     * @param source 达成来源，不可为 null —— null 曾会静默落进健康类分支、被标成
     *               {@code health_record}，把 AC5 那条「健康类 + checkin 即护栏失效」的告警洗白
     *               （code-review 2026-08-04）
     */
    public static String of(String code, MilestoneCompletionSource source) {
        Objects.requireNonNull(source, "source");
        // 穷尽 switch（**刻意不写 default**）：日后给 MilestoneCompletionSource 加第 4 个值
        // （运营补发 / 数据迁移回填）时这里会编译失败，逼后来人显式决定它的 path，
        // 而不是让新来源被静默归进 system_auto/health_record —— 误标方向恰好是把可疑组合洗白。
        return switch (source) {
            case USER_CHECKIN -> "checkin";
            case PUBLISH -> "publish";
            case SYSTEM_AUTO -> systemAutoPathOf(code);
        };
    }

    /**
     * SYSTEM_AUTO 再按 code 细分：问诊点亮的 → {@code consult}；其余健康类 → {@code health_record}；
     * 都不是 → 纯系统自动。
     *
     * <p>⚠️ 顺序不可调换：先认 {@link #CONSULT_CODES} 再看健康类。反过来会让「既是健康类、又由问诊
     * 点亮」的节点（C-M5 / D-M5，以及 Story 1.2 之后的 G-M1）被吞进 {@code health_record}。
     */
    private static String systemAutoPathOf(String code) {
        if (CONSULT_CODES.contains(code)) {
            return "consult";
        }
        return HealthMilestones.isHealthMilestone(code) ? "health_record" : "system_auto";
    }
}
