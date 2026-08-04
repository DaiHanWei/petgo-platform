package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.HealthMilestones;
import com.tailtopia.profile.domain.MilestoneCompletionSource;

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
 *   <li>SYSTEM_AUTO + 健康类 code：M5（第一次看兽医）→ {@code consult}；
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

    /** 与 {@link HealthMilestones#SUFFIXES} 同源：M5 是唯一由兽医问诊触发的那条。 */
    private static final String CONSULT_SUFFIX = "M5";

    private MilestoneAnalyticsPath() {
    }

    public static String of(String code, MilestoneCompletionSource source) {
        if (source == MilestoneCompletionSource.USER_CHECKIN) {
            return "checkin";
        }
        if (source == MilestoneCompletionSource.PUBLISH) {
            return "publish";
        }
        if (HealthMilestones.isHealthMilestone(code)) {
            return suffixOf(code).equals(CONSULT_SUFFIX) ? "consult" : "health_record";
        }
        return "system_auto";
    }

    private static String suffixOf(String code) {
        int dash = code == null ? -1 : code.lastIndexOf('-');
        return dash >= 0 ? code.substring(dash + 1) : String.valueOf(code);
    }
}
