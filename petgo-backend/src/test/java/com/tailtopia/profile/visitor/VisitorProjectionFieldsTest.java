package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * L0：访客投影层的<b>结构性</b>安全守卫（V1.1.6 Story 2.1 · AC2）。
 *
 * <p><b>这组测试守的不是行为，是形状。</b>「访客看不到健康记录」这条规则，
 * 如果只靠每次改代码的人记得过滤，那它迟早会破 —— 而破的那天<b>不会有任何东西报错</b>，
 * 直到有人发现自己的健康记录出现在了别人手机上。
 *
 * <p>所以这里把规则钉在结构上：
 * <ol>
 *   <li>投影层<b>不持有</b>任何健康 / 问诊仓库 —— 拿不到就不可能泄露</li>
 *   <li>访客 DTO <b>没有</b>健康相关字段 —— 装不下就不可能填错</li>
 * </ol>
 * 谁将来加了，这两条立刻红，而且报错会直接告诉他为什么不该加。
 */
class VisitorProjectionFieldsTest {

    /** 一看就与健康 / 问诊有关的字样。命中即拒。 */
    private static final List<String> FORBIDDEN_HINTS = List.of(
            "health", "consult", "symptom", "diagnos", "medical", "triage", "ailevel");

    /**
     * 精确白名单 —— <b>只有这一个</b>。
     *
     * <p>⚠️ 「问诊<b>次数</b>」与「问诊<b>记录</b>」是两件事：
     * 次数只是一个计数，不暴露任何问诊内容、结论或发生时间，
     * <b>2026-08-06 产品确认保留</b>（PRD §2.9 §② 那张表里它是 ✅）；记录本身是 ❌。
     *
     * <p>所以这里放行 {@code consultCount} 这一个确切的名字，
     * 而不是把 {@code consult} 整个从禁词里删掉 —— 那样
     * {@code consultRecord} / {@code consultSummary} / {@code consultConclusion} 就全漏了。
     */
    private static final List<String> EXPLICITLY_ALLOWED = List.of("consultcount");

    /**
     * 🛡 投影层<b>不得持有</b>任何健康 / 问诊数据源。
     *
     * <p>作为对照：作者态的 {@code TimelineService} 持有 {@code HealthRecordRepository} 与
     * {@code HealthEventTimelineSource} —— 那正是本层不得照搬的部分。
     *
     * <p>⚠️ <b>白名单里的例外只有一个</b>：{@code TimelineService} 本身。
     * 本层向它借 {@code getStats}（好让作者页与访客页的统计数字出自同一个实现），
     * 但<b>不借它任何健康相关能力</b>。这是一个经过权衡的例外，不是口子 ——
     * 加第二个例外之前请先想清楚。
     */
    @Test
    void projectionServiceHoldsNoHealthOrConsultRepository() {
        List<String> offenders = new ArrayList<>();
        for (Field f : VisitorProjectionService.class.getDeclaredFields()) {
            String typeName = f.getType().getSimpleName().toLowerCase(Locale.ROOT);
            // 唯一例外：TimelineService（只为复用 getStats，见类 Javadoc）
            if (f.getType().getSimpleName().equals("TimelineService")) {
                continue;
            }
            for (String hint : FORBIDDEN_HINTS) {
                if (typeName.contains(hint)) {
                    offenders.add(f.getName() + " : " + f.getType().getSimpleName());
                }
            }
        }
        assertThat(offenders)
                .as("访客投影层持有了健康 / 问诊数据源：%s\n"
                        + "这一层的依赖清单就是它的安全边界 —— 拿不到才不可能泄露。"
                        + "如果确实需要某个数字（比如问诊次数），请向 TimelineService.getStats() 要，"
                        + "而不是直接持有仓库。", offenders)
                .isEmpty();
    }

    /**
     * 🛡 访客时间线条目<b>不得有</b>健康相关字段。
     *
     * <p>作者态的 {@code TimelineItemResponse} 带着 {@code aiLevel}、
     * <b>{@code symptomSummary}（症状摘要，这就是健康数据本身）</b>、
     * {@code healthRecordType}、{@code healthRecordId}。
     * 访客侧另起一个 record，就是为了让这些字段<b>物理上不存在</b>。
     */
    @Test
    void visitorTimelineItemHasNoHealthFields() {
        assertNoForbiddenComponents(VisitorTimelineItem.class);
    }

    /**
     * 🛡 访客统计<b>只有三列</b>，不得混进健康记录条数。
     *
     * <p>作者态的 {@code ArchiveStatsResponse} 有第 5 个字段 {@code healthRecordCount}。
     * 「复用统计实现」不等于「原样透传那个对象」—— 条数虽不是内容，
     * 却足以推断出「这只宠物有没有健康问题记录」。
     */
    @Test
    void visitorStatsHasNoHealthRecordCount() {
        assertNoForbiddenComponents(VisitorStats.class);
        // 顺带钉住列数：三列的口径来自 PRD §2.9（Diary / 问诊次数 / 里程碑），
        // 里程碑占两个数（完成 / 总数），故是 4 个组件。
        assertThat(VisitorStats.class.getRecordComponents()).hasSize(4);
    }

    /**
     * 🛡 访客日历格子<b>不得有</b>健康相关字段（V1.1.6 Story 2.2）。
     *
     * <p>作者态的 {@code CalendarMonthResponse.DayCell} 有
     * {@code hasHealthEvent} · {@code healthRecordType} · {@code healthRecordCount} 三个。
     * 哪怕只下发一个布尔值 {@code hasHealthEvent=true}，也等于告诉陌生人
     * <b>「这只宠物这天看过病」</b> —— 那正是 PRD §2.9 整块禁止的东西。
     */
    @Test
    void visitorDayCellHasNoHealthFields() {
        assertNoForbiddenComponents(VisitorDayCell.class);
        assertThat(VisitorDayCell.class.getRecordComponents())
                .as("VisitorDayCell 的字段数变了。它刻意只有三个（day / firstImageUrl / hasHappyMoment）—— "
                        + "作者态那个有六个，多出来的三个全是健康相关，不该被加回来。")
                .hasSize(3);
    }

    /** 🛡 访客日历的月容器同样过一遍（它只是壳，但壳上也不该长出健康字段）。 */
    @Test
    void visitorCalendarMonthHasNoHealthFields() {
        assertNoForbiddenComponents(VisitorCalendarMonth.class);
    }

    /**
     * 🛡 访客档案<b>不得</b>出现内部标识或健康字段（V1.1.6 Story 2.3）。
     *
     * <p>作者态的档案对象带着 {@code ownerId} · 自增 {@code id} · {@code cardToken} ·
     * {@code serialId}。前两个是<b>可枚举的内部标识</b>（架构护栏：对外一律用不可枚举 token），
     * 后两个访客本来就不该拿到。这个 record 是白名单，不是「作者档案删几个字段」。
     */
    @Test
    void visitorProfileCarriesNoInternalIdentifiers() {
        assertNoForbiddenComponents(VisitorProfileResponse.class);
        List<String> leaked = new ArrayList<>();
        for (RecordComponent rc : VisitorProfileResponse.class.getRecordComponents()) {
            String name = rc.getName().toLowerCase(Locale.ROOT);
            // 「id」要精确匹配：ownerId / cardToken / serialId 该拦，
            // 但将来若出现合法的含 id 词（如 idCardIssued）不该误伤，故只列确切名字。
            if (List.of("id", "ownerid", "cardtoken", "serialid", "ogimageurl").contains(name)) {
                leaked.add(rc.getName());
            }
        }
        assertThat(leaked)
                .as("访客档案里出现了内部标识：%s —— 这些是可枚举的内部 id 或不该回显的 token，"
                        + "访客档案是白名单 record，加字段前先问「陌生人有必要知道这个吗」", leaked)
                .isEmpty();
    }

    /** 🛡 访客 DTO 里也不能出现任何拉黑相关信号（AD-1 Rule 9 的附带红线）。 */
    @Test
    void visitorDtosLeakNoBlockSignal() {
        for (Class<?> type : List.of(VisitorTimelineItem.class, VisitorStats.class,
                VisitorDayCell.class, VisitorCalendarMonth.class, VisitorProfileResponse.class)) {
            for (RecordComponent rc : type.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("%s.%s 看起来在下发拉黑相关信号 —— 那等于向查看者暴露"
                                + "自己的拉黑名单在此处是否生效，也给了对方探测的余地", type.getSimpleName(), rc.getName())
                        .doesNotContain("block")
                        .doesNotContain("hidden")
                        .doesNotContain("muted");
            }
        }
    }

    private static void assertNoForbiddenComponents(Class<?> recordType) {
        List<String> offenders = new ArrayList<>();
        for (RecordComponent rc : recordType.getRecordComponents()) {
            String name = rc.getName().toLowerCase(Locale.ROOT);
            if (EXPLICITLY_ALLOWED.contains(name)) {
                continue; // 见 EXPLICITLY_ALLOWED 的说明：次数可给，记录不可
            }
            for (String hint : FORBIDDEN_HINTS) {
                if (name.contains(hint)) {
                    offenders.add(rc.getName());
                }
            }
        }
        assertThat(offenders)
                .as("%s 里出现了健康 / 问诊相关字段：%s\n"
                        + "访客 DTO 是刻意新建的容器，就是为了让这些字段物理上不存在 —— "
                        + "加回去等于把安全规则重新交还给「记得别填」。", recordType.getSimpleName(), offenders)
                .isEmpty();
    }
}
