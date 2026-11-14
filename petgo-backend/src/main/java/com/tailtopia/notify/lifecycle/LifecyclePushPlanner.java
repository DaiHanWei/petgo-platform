package com.tailtopia.notify.lifecycle;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.port.LifecycleContextPort.DefaultPet;
import com.tailtopia.notify.port.LifecycleContextPort.LifecycleContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 生命周期推送计划器（留存运营作战手册 · 抓手 1；V1.5.0 onboarding-recall Story 6.1 · OR-22 / OR-23 重写为五层分层）——
 * <b>纯逻辑、无副作用</b>，是 L0 金标可测的核心。
 *
 * <p>分层（发送当时按账号判定，自上而下取第一个，见 {@link LifecycleLayer}）：A 没宠物 / B 待建档 / C 待发帖 / D 待互动 / E 已互动。
 *
 * <table border="1">
 *   <caption>节点 × 分层 → 变体 · 文案键</caption>
 *   <tr><th>节点</th><th>A</th><th>B</th><th>C</th><th>D</th><th>E</th></tr>
 *   <tr><td>D1</td><td>DISCOVER（③④⑤ 任一完成 → FEED）· NO_PET</td><td>CREATE_PROFILE</td><td>RECORD</td>
 *       <td>DISCOVER · SQUAD</td><td>RECORD · ACTIVE</td></tr>
 *   <tr><td>D3</td><td>同 D1 · NO_PET</td><td>CREATE_PROFILE</td><td>DISCOVER · SQUAD</td>
 *       <td>DISCOVER · BESTIE</td><td><b>不推</b>（不写 mark）</td></tr>
 *   <tr><td>D7</td><td colspan="1">毕业 → GRADUATED；否则 NEWBIE_TASKS · TASKS_LEFT</td><td>CREATE_PROFILE</td>
 *       <td>同 A</td><td>同 A</td><td>同 A</td></tr>
 * </table>
 *
 * <ul>
 *   <li>D1 / D3 当天已来过（{@code activeToday}）→ 产出 {@code SKIPPED_ACTIVE}：dispatcher 只写 mark（作废本节点，
 *       窗口第二天不补），不推送、不写通知。D7 不受影响。D3 的 E 层先于此判定（不产出任何东西）。</li>
 *   <li>毕业 = 已有 {@code LULUS_PEMULA} flag，<b>或</b> ①–⑥ 标记齐全（⑦ 在 D7 窗口按注册日必然满足）；
 *       N = 7 − 已完成项（⑦ 计为完成）。</li>
 *   <li>一人一天至多一条（if / else-if 链）、节点 2 天窗口、按 D1 → D3 → D7 → 召回排序，不变。</li>
 *   <li>召回（{@code LIFECYCLE_WINBACK}）只在 {@link LifecyclePlanOptions#winbackEnabled()} 时产出，规则不变。</li>
 * </ul>
 *
 * <p>本类<b>绝不</b>读写 DB / 发推送 / 看时钟 —— {@code today}（WIB）由调用方注入，便于 L0 测试。
 */
@Component
public class LifecyclePushPlanner {

    /** 节点触发窗口宽度（天）：注册第 N ~ N+1 天内都可触发，容忍日扫漏跑一次。 */
    static final int WINDOW_DAYS = 2;

    /** 三个固定节点的锚点天数。 */
    static final int D1 = 1;
    static final int D3 = 3;
    static final int D7 = 7;

    /** 新手任务 ①–⑥（⑦ ONE_WEEK 在 D7 窗口按注册日必然满足，不看标记）。 */
    static final List<String> NEWBIE_FIRST_SIX = List.of("FIRST_DIARY", "FIRST_LIKE_RECEIVED", "FIRST_FOLLOW",
            "FIRST_LIKE_GIVEN", "FIRST_COMMENT", "FIRST_REPLY");

    /** 互动类新手任务 ③④⑤：决定 D / E 层与 A 层 D1 / D3 的 FEED 分支。 */
    static final Set<String> INTERACTION_MARKS = Set.of("FIRST_FOLLOW", "FIRST_LIKE_GIVEN", "FIRST_COMMENT");

    static final String HAS_PET = "HAS_PET";

    /** 召回节点的 nodeKey 格式：每月至多召回一次。 */
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * @param today   当天（WIB，注入便于 L0 测试）。
     * @param users   候选用户快照（已在只读端口剔除兽医/管理员/虚拟账号/注销/封禁；注册日 WIB）。
     * @param ctx     分层上下文（默认宠物 / 新手标记 / 今日活跃 / 毕业）。
     * @param options 召回开关等。
     * @return 当日应推集合（含只写 mark 的 {@code SKIPPED_ACTIVE}），按 D1 → D3 → D7 → 召回排序。
     */
    public List<LifecyclePlannedPush> plan(LocalDate today, List<UserLifecycleSnapshot> users, LifecycleContext ctx,
            LifecyclePlanOptions options) {
        LifecycleContext c = ctx == null ? LifecycleContext.EMPTY : ctx;
        LifecyclePlanOptions o = options == null ? LifecyclePlanOptions.DEFAULT : options;
        List<LifecyclePlannedPush> out = new ArrayList<>();
        for (UserLifecycleSnapshot u : users) {
            if (u.registeredDate() == null) {
                continue;
            }
            long age = ChronoUnit.DAYS.between(u.registeredDate(), today);
            if (age < 0) {
                continue; // 未来注册日 = 脏数据
            }
            DefaultPet pet = c.defaultPets().get(u.userId());
            Set<String> marks = c.marksOf(u.userId());
            LifecycleLayer layer = layerOf(u, pet, marks);
            LifecyclePlannedPush p;
            if (inWindow(age, D1)) {
                p = earlyNode(u, NotificationType.LIFECYCLE_D1, layer, pet, marks, c);
            } else if (inWindow(age, D3)) {
                p = earlyNode(u, NotificationType.LIFECYCLE_D3, layer, pet, marks, c);
            } else if (inWindow(age, D7)) {
                p = d7(u, layer, pet, marks, c);
            } else if (o.winbackEnabled() && age >= D7 + WINDOW_DAYS && isLapsed(today, u, o.winbackAfterDays())) {
                LifecycleVariant v = pet != null ? LifecycleVariant.RECORD : LifecycleVariant.CREATE_PROFILE;
                p = build(u, NotificationType.LIFECYCLE_WINBACK, MONTH_KEY.format(today), v, v.name(), layer, pet, 0);
            } else {
                p = null;
            }
            if (p != null) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparingInt(x -> urgency(x.type())));
        return out;
    }

    /** OR-22：自上而下取第一个。 */
    static LifecycleLayer layerOf(UserLifecycleSnapshot u, DefaultPet pet, Set<String> marks) {
        if (pet == null) {
            return HAS_PET.equals(u.petStatus()) ? LifecycleLayer.B : LifecycleLayer.A;
        }
        if (!u.hasPublished()) {
            return LifecycleLayer.C;
        }
        return marks.stream().anyMatch(INTERACTION_MARKS::contains) ? LifecycleLayer.E : LifecycleLayer.D;
    }

    /** D1 / D3（OR-23 前两行）；返回 null = 不推也不写 mark（D3 E 层）。 */
    private LifecyclePlannedPush earlyNode(UserLifecycleSnapshot u, NotificationType type, LifecycleLayer layer,
            DefaultPet pet, Set<String> marks, LifecycleContext c) {
        boolean d1 = type == NotificationType.LIFECYCLE_D1;
        if (!d1 && layer == LifecycleLayer.E) {
            return null; // AC6：D3 已互动不推、不写 mark
        }
        LifecycleVariant variant;
        String copy;
        switch (layer) {
            case A -> {
                variant = marks.stream().anyMatch(INTERACTION_MARKS::contains) ? LifecycleVariant.FEED
                        : LifecycleVariant.DISCOVER;
                copy = "NO_PET";
            }
            case B -> {
                variant = LifecycleVariant.CREATE_PROFILE;
                copy = "CREATE_PROFILE";
            }
            case C -> {
                variant = d1 ? LifecycleVariant.RECORD : LifecycleVariant.DISCOVER;
                copy = d1 ? "RECORD" : "SQUAD";
            }
            case D -> {
                variant = LifecycleVariant.DISCOVER;
                copy = d1 ? "SQUAD" : "BESTIE";
            }
            default -> {
                variant = LifecycleVariant.RECORD;
                copy = "ACTIVE";
            }
        }
        if (c.activeToday().contains(u.userId())) {
            // AC5：当天已来过 → 作废本节点（只写 mark，不推不写通知）
            return build(u, type, LifecyclePlannedPush.ONCE, LifecycleVariant.SKIPPED_ACTIVE, copy, layer, pet, 0);
        }
        return build(u, type, LifecyclePlannedPush.ONCE, variant, copy, layer, pet, 0);
    }

    /** D7（OR-23 第三行）：B → 建档；其余毕业 / 还差 N 项（账号级内容）。 */
    private LifecyclePlannedPush d7(UserLifecycleSnapshot u, LifecycleLayer layer, DefaultPet pet,
            Set<String> marks, LifecycleContext c) {
        if (layer == LifecycleLayer.B) {
            return build(u, NotificationType.LIFECYCLE_D7, LifecyclePlannedPush.ONCE,
                    LifecycleVariant.CREATE_PROFILE, "CREATE_PROFILE", layer, pet, 0);
        }
        int done = (int) NEWBIE_FIRST_SIX.stream().filter(marks::contains).count() + 1; // ⑦ 计为完成
        boolean graduated = c.graduated().contains(u.userId()) || done >= NEWBIE_FIRST_SIX.size() + 1;
        if (graduated) {
            return build(u, NotificationType.LIFECYCLE_D7, LifecyclePlannedPush.ONCE, LifecycleVariant.GRADUATED,
                    "GRADUATED", layer, pet, 0);
        }
        return build(u, NotificationType.LIFECYCLE_D7, LifecyclePlannedPush.ONCE, LifecycleVariant.NEWBIE_TASKS,
                "TASKS_LEFT", layer, pet, NEWBIE_FIRST_SIX.size() + 1 - done);
    }

    private static LifecyclePlannedPush build(UserLifecycleSnapshot u, NotificationType type, String nodeKey,
            LifecycleVariant variant, String copy, LifecycleLayer layer, DefaultPet pet, int remaining) {
        String petName = pet == null || pet.name() == null || pet.name().isBlank() ? null : pet.name();
        String breed = pet == null || pet.breed() == null || pet.breed().isBlank() ? null : pet.breed().trim();
        return new LifecyclePlannedPush(u.userId(), type, nodeKey, variant, petName, layer, copy, breed,
                pet == null ? null : pet.petType(), remaining);
    }

    /** 注册天数是否落在节点 {@code anchor} 的触发窗口 {@code [anchor, anchor+1]} 内。 */
    private boolean inWindow(long age, int anchor) {
        return age >= anchor && age < anchor + WINDOW_DAYS;
    }

    /**
     * 是否已流失。{@code lastActiveDate} 为空（回填前的老数据）时退化为按注册日算 ——
     * 宁可把「其实还活跃但没记录」的人误判为流失（代价是一条召回推送），
     * 也不要因为缺一个字段就把整批存量用户永久排除在召回之外。
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
}
