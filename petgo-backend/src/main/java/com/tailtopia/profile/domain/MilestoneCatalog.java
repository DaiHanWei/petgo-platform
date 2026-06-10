package com.petgo.profile.domain;

import static com.petgo.profile.domain.MilestoneLevel.L;
import static com.petgo.profile.domain.MilestoneLevel.M;
import static com.petgo.profile.domain.MilestoneLevel.S;
import static com.petgo.profile.domain.MilestoneTriggerType.PUSH_PUBLISH;
import static com.petgo.profile.domain.MilestoneTriggerType.SYSTEM_AUTO;
import static com.petgo.profile.domain.MilestoneTriggerType.USER_CHECKIN;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 里程碑清单后端固定常量（FR-42 / 决策 F16）。**清单的唯一事实源**——猫 30 / 狗 30 / 通用 15。
 *
 * <p>V1 不做运营可编辑清单（护栏）：清单为编译期常量；DB 的 {@code pet_milestones} roster 仅是建档时
 * 按本常量物化的 per-pet 副本（承载结构位供查询自包含），标题/级别/触发/组合依赖一律以本类为准。
 *
 * <p>组合依赖（C-Lx）：{@link #HEALTH_COMBO} 给出「健康里程碑全完成」节点对其前置项的依赖
 * （C-L4 = C-M3+C-M4+C-M5；D-L4 同），8.3 在前置全完成后自动解锁。
 */
public final class MilestoneCatalog {

    private MilestoneCatalog() {
    }

    private static final List<MilestoneDefinition> CAT = buildCat();
    private static final List<MilestoneDefinition> DOG = buildDog();
    private static final List<MilestoneDefinition> OTHER = buildOther();

    private static final Map<String, MilestoneDefinition> BY_CODE = indexByCode(CAT, DOG, OTHER);

    /**
     * 「健康里程碑全完成」组合依赖：节点 code → 前置 code 集合。前置全部完成后自动解锁（8.3，SYSTEM_AUTO）。
     */
    public static final Map<String, Set<String>> HEALTH_COMBO = Map.of(
            "C-L4", Set.of("C-M3", "C-M4", "C-M5"),
            "D-L4", Set.of("D-M3", "D-M4", "D-M5"));

    /** 按宠物类型返回有序固定清单（不可变）。 */
    public static List<MilestoneDefinition> forType(PetType petType) {
        return switch (petType) {
            case CAT -> CAT;
            case DOG -> DOG;
            case OTHER -> OTHER;
        };
    }

    /** 按 code 查定义（roster 富化标题/级别/触发用）；未知 code → null。 */
    public static MilestoneDefinition byCode(String code) {
        return BY_CODE.get(code);
    }

    // ----------------------------------------------------------------------------------------
    // 🐱 猫咪里程碑清单（共 30：S15 / M10 / L5）
    // ----------------------------------------------------------------------------------------
    private static List<MilestoneDefinition> buildCat() {
        Seq q = new Seq("C");
        return List.of(
                // S 级（15）
                q.s(SYSTEM_AUTO, "宠物档案创建完成"),          // C-S1
                q.s(SYSTEM_AUTO, "第一张照片上传到成长日历"),   // C-S2
                q.s(SYSTEM_AUTO, "第一次分享宠物名片"),         // C-S3
                q.s(SYSTEM_AUTO, "第一次保存兽医问诊结论"),     // C-S4
                q.s(SYSTEM_AUTO, "第一次发布日常分享"),         // C-S5
                q.s(USER_CHECKIN, "第一次洗澡"),               // C-S6
                q.s(USER_CHECKIN, "第一次修剪指甲"),           // C-S7
                q.s(USER_CHECKIN, "第一次吃零食"),             // C-S8
                q.s(USER_CHECKIN, "第一次睡在你身边"),         // C-S9
                q.s(USER_CHECKIN, "第一次发出咕噜声"),         // C-S10
                q.s(USER_CHECKIN, "第一次在窗边晒太阳"),       // C-S11
                q.s(USER_CHECKIN, "第一次玩逗猫棒"),           // C-S12
                q.s(USER_CHECKIN, "第一次钻进纸箱"),           // C-S13
                q.s(SYSTEM_AUTO, "第一次被评论"),              // C-S14
                q.s(SYSTEM_AUTO, "第一次收到点赞"),            // C-S15
                // M 级（10）
                q.m(USER_CHECKIN, "第一次出门探险"),           // C-M1
                q.m(USER_CHECKIN, "第一次坐车"),               // C-M2
                q.m(USER_CHECKIN, "完成第一次疫苗接种"),       // C-M3
                q.m(USER_CHECKIN, "完成第一次驱虫"),           // C-M4
                q.m(USER_CHECKIN, "第一次看兽医"),             // C-M5
                q.m(USER_CHECKIN, "第一次见到其他猫咪"),       // C-M6
                q.m(USER_CHECKIN, "学会回应自己的名字"),       // C-M7
                q.m(SYSTEM_AUTO, "陪伴满 30 天"),              // C-M8
                q.m(USER_CHECKIN, "完成绝育手术"),             // C-M9
                q.m(SYSTEM_AUTO, "成长日历记录满 10 条"),       // C-M10
                // L 级（5）
                q.l(PUSH_PUBLISH, "第一个生日 🎂"),            // C-L1
                q.l(PUSH_PUBLISH, "陪伴满 100 天"),            // C-L2
                q.l(PUSH_PUBLISH, "陪伴满 365 天"),            // C-L3
                q.l(SYSTEM_AUTO, "完成全部健康里程碑"),         // C-L4
                q.l(SYSTEM_AUTO, "成长日历记录满 30 条"));      // C-L5
    }

    // ----------------------------------------------------------------------------------------
    // 🐶 狗狗里程碑清单（共 30：S15 / M10 / L5）
    // ----------------------------------------------------------------------------------------
    private static List<MilestoneDefinition> buildDog() {
        Seq q = new Seq("D");
        return List.of(
                // S 级（15）
                q.s(SYSTEM_AUTO, "宠物档案创建完成"),          // D-S1
                q.s(SYSTEM_AUTO, "第一张照片上传到成长日历"),   // D-S2
                q.s(SYSTEM_AUTO, "第一次分享宠物名片"),         // D-S3
                q.s(SYSTEM_AUTO, "第一次保存兽医问诊结论"),     // D-S4
                q.s(SYSTEM_AUTO, "第一次发布日常分享"),         // D-S5
                q.s(USER_CHECKIN, "第一次洗澡"),               // D-S6
                q.s(USER_CHECKIN, "第一次美容 / 梳毛"),         // D-S7
                q.s(USER_CHECKIN, "第一次吃零食"),             // D-S8
                q.s(USER_CHECKIN, "第一次睡在你身边"),         // D-S9
                q.s(USER_CHECKIN, "第一次摇尾巴"),             // D-S10
                q.s(USER_CHECKIN, "第一次戴项圈 / 牵引绳"),     // D-S11
                q.s(USER_CHECKIN, "第一次玩球"),               // D-S12
                q.s(USER_CHECKIN, "第一次游泳 / 玩水"),         // D-S13
                q.s(SYSTEM_AUTO, "第一次被评论"),              // D-S14
                q.s(SYSTEM_AUTO, "第一次收到点赞"),            // D-S15
                // M 级（10）
                q.m(USER_CHECKIN, "第一次出门散步"),           // D-M1
                q.m(USER_CHECKIN, "第一次坐车"),               // D-M2
                q.m(USER_CHECKIN, "完成第一次疫苗接种"),       // D-M3
                q.m(USER_CHECKIN, "完成第一次驱虫"),           // D-M4
                q.m(USER_CHECKIN, "第一次看兽医"),             // D-M5
                q.m(USER_CHECKIN, "第一次见到其他狗狗"),       // D-M6
                q.m(USER_CHECKIN, "学会第一个指令"),           // D-M7
                q.m(SYSTEM_AUTO, "陪伴满 30 天"),              // D-M8
                q.m(USER_CHECKIN, "完成绝育手术"),             // D-M9
                q.m(SYSTEM_AUTO, "成长日历记录满 10 条"),       // D-M10
                // L 级（5）
                q.l(PUSH_PUBLISH, "第一个生日 🎂"),            // D-L1
                q.l(PUSH_PUBLISH, "陪伴满 100 天"),            // D-L2
                q.l(PUSH_PUBLISH, "陪伴满 365 天"),            // D-L3
                q.l(SYSTEM_AUTO, "完成全部健康里程碑"),         // D-L4
                q.l(SYSTEM_AUTO, "成长日历记录满 30 条"));      // D-L5
    }

    // ----------------------------------------------------------------------------------------
    // 🐾 通用里程碑清单（其他宠物，共 15：S8 / M4 / L3）
    // ----------------------------------------------------------------------------------------
    private static List<MilestoneDefinition> buildOther() {
        Seq q = new Seq("G");
        return List.of(
                // S 级（8）
                q.s(SYSTEM_AUTO, "宠物档案创建完成"),          // G-S1
                q.s(SYSTEM_AUTO, "第一张照片上传到成长日历"),   // G-S2
                q.s(SYSTEM_AUTO, "第一次分享宠物名片"),         // G-S3
                q.s(SYSTEM_AUTO, "第一次保存兽医问诊结论"),     // G-S4
                q.s(SYSTEM_AUTO, "第一次发布日常分享"),         // G-S5
                q.s(USER_CHECKIN, "第一次吃零食"),             // G-S6
                q.s(SYSTEM_AUTO, "第一次被评论"),              // G-S7
                q.s(SYSTEM_AUTO, "第一次收到点赞"),            // G-S8
                // M 级（4）
                q.m(USER_CHECKIN, "第一次看兽医"),             // G-M1
                q.m(USER_CHECKIN, "完成第一次健康检查 / 疫苗"), // G-M2
                q.m(SYSTEM_AUTO, "陪伴满 30 天"),              // G-M3
                q.m(SYSTEM_AUTO, "成长日历记录满 10 条"),       // G-M4
                // L 级（3）
                q.l(PUSH_PUBLISH, "第一个生日 🎂"),            // G-L1
                q.l(PUSH_PUBLISH, "陪伴满 100 天"),            // G-L2
                q.l(PUSH_PUBLISH, "陪伴满 365 天"));           // G-L3
    }

    /** 清单构造辅助：按级别自增编号生成 code（C-S1…），并维护全局 sortOrder。 */
    private static final class Seq {
        private final String prefix;
        private int s;
        private int m;
        private int l;
        private int order;

        Seq(String prefix) {
            this.prefix = prefix;
        }

        MilestoneDefinition s(MilestoneTriggerType t, String title) {
            return new MilestoneDefinition(prefix + "-S" + (++s), S, t, ++order, title);
        }

        MilestoneDefinition m(MilestoneTriggerType t, String title) {
            return new MilestoneDefinition(prefix + "-M" + (++m), M, t, ++order, title);
        }

        MilestoneDefinition l(MilestoneTriggerType t, String title) {
            return new MilestoneDefinition(prefix + "-L" + (++l), L, t, ++order, title);
        }
    }

    private static Map<String, MilestoneDefinition> indexByCode(List<MilestoneDefinition>... lists) {
        Map<String, MilestoneDefinition> idx = new LinkedHashMap<>();
        for (List<MilestoneDefinition> list : lists) {
            for (MilestoneDefinition d : list) {
                idx.put(d.code(), d);
            }
        }
        return Map.copyOf(idx);
    }
}
