package com.tailtopia.notify.lifecycle;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.notify.domain.NotificationType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 生命周期推送计划器（留存运营作战手册 · 抓手 1）—— <b>纯逻辑、无副作用</b>，是 L0 金标可测的核心。
 *
 * <p>给定「当天 + 用户生命周期快照 + 各人宠物名」，产出当日应投递的推送集合：
 *
 * <table border="1">
 *   <caption>四节点 × 分层</caption>
 *   <tr><th>节点</th><th>触发</th><th>分层 → 落点</th></tr>
 *   <tr><td>D1</td><td>注册满 1 天</td>
 *       <td>已建档→RECORD「今天记录 Mochi 的一个瞬间吧」；未建档→CREATE_PROFILE</td></tr>
 *   <tr><td>D3</td><td>注册满 3 天 <b>且仍未发布</b></td>
 *       <td>已建档→FEED「看看别人家宠物今天做了什么」；未建档→CREATE_PROFILE</td></tr>
 *   <tr><td>D7</td><td>注册满 7 天</td>
 *       <td>已发布→REVIEW 周回顾；已建档未发布→RECORD；未建档→CREATE_PROFILE</td></tr>
 *   <tr><td>召回</td><td>{@code lastActive} 距今 ≥ N 天，且已过 D7 窗口</td>
 *       <td>已建档→RECORD；未建档→CREATE_PROFILE（手册：ROI 最高的一刀）</td></tr>
 * </table>
 *
 * <h2>三条刻意的设计</h2>
 * <ol>
 *   <li><b>一人一天至多一条</b>：四个节点写成 if / else-if 链而非并列判定。同一天既收「记录 Mochi」
 *       又收「回来看看」，用户学到的不是「该记录了」，而是「这个 App 很吵」—— 直接换来关推送权限。</li>
 *   <li><b>节点用 2 天窗口而非「恰好第 N 天」</b>：日扫漏跑一次（发版/重启/时钟漂移）就该补上，
 *       但窗口又必须够窄 —— 若写成 {@code >= 1}，首次上线会给全部存量用户补发一轮 D1，
 *       「你昨天注册了」发给两个月前的老用户是纯粹的骚扰。窗口 + 去重表，两边都守住。</li>
 *   <li><b>召回按月去重、且只对过了 D7 的人发</b>：D1–D7 期间的人由前三个节点负责，
 *       不该再被算作「流失」重复触达。</li>
 * </ol>
 *
 * <p>本类<b>绝不</b>读写 DB / 发推送 / 看时钟 —— {@code today} 由调用方注入，便于 L0 测试。
 */
@Component
public class LifecyclePushPlanner {

    /** 节点触发窗口宽度（天）：注册第 N ~ N+1 天内都可触发，容忍日扫漏跑一次。 */
    static final int WINDOW_DAYS = 2;

    /** 三个固定节点的锚点天数。 */
    static final int D1 = 1;
    static final int D3 = 3;
    static final int D7 = 7;

    /** 召回节点的 nodeKey 格式：每月至多召回一次。 */
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * @param today            当天（UTC，注入便于 L0 测试）。
     * @param users            候选用户快照（已在只读端口剔除兽医/管理员/虚拟账号/注销/封禁）。
     * @param petNameByOwner   owner → 宠物名；<b>键存在即视为已建档</b>（值可为空串）。
     * @param winbackAfterDays 「多久没回来算流失」，默认 7（手册定义）。
     * @return 当日应推集合，按「节点紧迫度」排序（D1 → D3 → D7 → 召回），
     *         调用方按每日投递上限截断时先保住时效性最强的 D1。
     */
    public List<LifecyclePlannedPush> plan(LocalDate today, List<UserLifecycleSnapshot> users,
            Map<Long, String> petNameByOwner, int winbackAfterDays) {
        List<LifecyclePlannedPush> out = new ArrayList<>();
        for (UserLifecycleSnapshot u : users) {
            if (u.registeredDate() == null) {
                continue; // 无注册日无从算节点（理论上不会有，NOT NULL 列）。
            }
            long age = ChronoUnit.DAYS.between(u.registeredDate(), today);
            if (age < 0) {
                continue; // 未来注册日 = 脏数据，跳过而不是算出负数天。
            }
            boolean hasProfile = petNameByOwner.containsKey(u.userId());
            String petName = petNameByOwner.get(u.userId());

            if (inWindow(age, D1)) {
                out.add(push(u, NotificationType.LIFECYCLE_D1, LifecyclePlannedPush.ONCE,
                        hasProfile ? LifecycleVariant.RECORD : LifecycleVariant.CREATE_PROFILE, petName));
            } else if (inWindow(age, D3) && !u.hasPublished()) {
                // 已发布的人不需要 D3 内容钩子 —— 他已经完成了我们想要的动作。
                out.add(push(u, NotificationType.LIFECYCLE_D3, LifecyclePlannedPush.ONCE,
                        hasProfile ? LifecycleVariant.FEED : LifecycleVariant.CREATE_PROFILE, petName));
            } else if (inWindow(age, D7)) {
                LifecycleVariant v = u.hasPublished() ? LifecycleVariant.REVIEW
                        : hasProfile ? LifecycleVariant.RECORD : LifecycleVariant.CREATE_PROFILE;
                out.add(push(u, NotificationType.LIFECYCLE_D7, LifecyclePlannedPush.ONCE, v, petName));
            } else if (age >= D7 + WINDOW_DAYS && isLapsed(today, u, winbackAfterDays)) {
                out.add(push(u, NotificationType.LIFECYCLE_WINBACK, MONTH_KEY.format(today),
                        hasProfile ? LifecycleVariant.RECORD : LifecycleVariant.CREATE_PROFILE, petName));
            }
        }
        out.sort(Comparator.comparingInt(p -> urgency(p.type())));
        return out;
    }

    /** 注册天数是否落在节点 {@code anchor} 的触发窗口 {@code [anchor, anchor+1]} 内。 */
    private boolean inWindow(long age, int anchor) {
        return age >= anchor && age < anchor + WINDOW_DAYS;
    }

    /**
     * 是否已流失。{@code lastActiveDate} 为空（回填前的老数据）时退化为按注册日算 ——
     * 宁可把「其实还活跃但没记录」的人误判为流失（代价是一条召回推送），
     * 也不要因为缺一个字段就把整批存量用户永久排除在召回之外，那才是手册最想捞回来的人。
     */
    private boolean isLapsed(LocalDate today, UserLifecycleSnapshot u, int winbackAfterDays) {
        LocalDate lastSeen = u.lastActiveDate() != null ? u.lastActiveDate() : u.registeredDate();
        return ChronoUnit.DAYS.between(lastSeen, today) >= winbackAfterDays;
    }

    /** 排序权重：越小越先投递（每日上限截断时先保 D1）。 */
    private int urgency(NotificationType type) {
        return switch (type) {
            case LIFECYCLE_D1 -> 0;
            case LIFECYCLE_D3 -> 1;
            case LIFECYCLE_D7 -> 2;
            default -> 3;
        };
    }

    private LifecyclePlannedPush push(UserLifecycleSnapshot u, NotificationType type, String nodeKey,
            LifecycleVariant variant, String petName) {
        return new LifecyclePlannedPush(u.userId(), type, nodeKey, variant,
                variant == LifecycleVariant.CREATE_PROFILE ? null : petName);
    }
}
