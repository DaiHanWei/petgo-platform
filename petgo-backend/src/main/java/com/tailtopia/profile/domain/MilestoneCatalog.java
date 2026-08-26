package com.tailtopia.profile.domain;

import static com.tailtopia.profile.domain.MilestoneLevel.L;
import static com.tailtopia.profile.domain.MilestoneLevel.M;
import static com.tailtopia.profile.domain.MilestoneLevel.S;
import static com.tailtopia.profile.domain.MilestoneTriggerType.PUSH_PUBLISH;
import static com.tailtopia.profile.domain.MilestoneTriggerType.SYSTEM_AUTO;
import static com.tailtopia.profile.domain.MilestoneTriggerType.USER_CHECKIN;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 里程碑清单后端固定常量（FR-42 / 决策 F16；7.3 聚合里程碑 +1）。**清单的唯一事实源**——猫 31 / 狗 31 / 通用 16。
 *
 * <p>V1 不做运营可编辑清单（护栏）：清单为编译期常量；DB 的 {@code pet_milestones} roster 仅是建档时
 * 按本常量物化的 per-pet 副本（承载结构位供查询自包含），标题/级别/触发/组合依赖一律以本类为准。
 *
 * <p>组合依赖（C-Lx）：{@link #HEALTH_COMBO} 给出「健康里程碑全完成」节点对其前置项的依赖
 * （C-L4 = C-M3+C-M4+C-M5；D-L4 同），8.3 在前置全完成后自动解锁。
 *
 * <p><b>⚠️ 改动约定：新增 / 改名 / 删除里程碑时，下列三处必须同步</b>（V1.1.6 Story 1.2）：
 * <ol>
 *   <li>本文件的 <b>{@code titleZh}</b>（中文，运营与内部用）</li>
 *   <li>本文件的 <b>{@code titleId}</b>（印尼语，<b>H5 分享页</b>用 —— 那是服务端渲染的 Thymeleaf，
 *       拿不到 App 的客户端本地化，故后端必须自带一份）</li>
 *   <li>App 的 <b>{@code petgo_app/lib/features/profile/domain/milestone_titles.dart}</b>
 *       （{@code kMilestoneTitles}，en + id 双语，App 内一切显示都走它）</li>
 * </ol>
 * 第 2 项的取值<b>逐条来自</b>第 3 项，不是另行翻译的。走散的表现是：同一个里程碑在 App 里叫一个名、
 * 在分享页叫另一个名，而用户完全可能同时看到这两处。
 * {@code MilestoneCatalogI18nTest} 会跨代码库逐条比对，漏改任一处即红 —— 这条比本段文字更硬。
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

    /**
     * 聚合里程碑「Lulus Pemula」（新手毕业，Story 7.3 / FR-47）的 5 个里程碑前置**语义后缀**。
     * S1–S5 在 CAT/DOG/OTHER 三清单语义一致、后缀统一（G-S1..G-S5 同义），故可用统一 suffix 判定。
     * 第 6 任务「录入一条健康记录」非里程碑节点，取 {@code health_records} 存在性，判定在
     * {@code MilestoneCompletionService.maybeUnlockLulusPemula} 内联（不塞进本目录）。
     */
    public static final Set<String> NEWBIE_PREREQ_SUFFIXES = Set.of("S1", "S2", "S3", "S4", "S5");

    /** Lulus Pemula 按 pet_type 的 code（catalog 末位 S 节点：CAT/DOG=S16、OTHER=S9）。 */
    public static String lulusPemulaCode(PetType petType) {
        return switch (petType) {
            case CAT -> "C-S16";
            case DOG -> "D-S16";
            case OTHER -> "G-S9";
        };
    }

    /** 某 code 是否 Lulus Pemula 聚合节点。 */
    public static boolean isLulusPemula(String code) {
        return "C-S16".equals(code) || "D-S16".equals(code) || "G-S9".equals(code);
    }

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
    // 🐱 猫咪里程碑清单（共 31：S16(含 Lulus Pemula) / M10 / L5）
    // ----------------------------------------------------------------------------------------
    private static List<MilestoneDefinition> buildCat() {
        Seq q = new Seq("C");
        return List.of(
                // S 级（15）
                q.s(SYSTEM_AUTO, "宠物档案创建完成", "Profil dibuat"),          // C-S1
                q.s(SYSTEM_AUTO, "第一张照片上传到成长日历", "Foto pertama di kalender"),   // C-S2
                q.s(SYSTEM_AUTO, "第一次分享宠物名片", "Kartu pertama dibagikan"),         // C-S3
                q.s(SYSTEM_AUTO, "第一次保存兽医问诊结论", "Catatan dokter pertama"),     // C-S4
                q.s(SYSTEM_AUTO, "第一次发布日常分享", "Postingan harian pertama"),         // C-S5
                q.s(USER_CHECKIN, "第一次洗澡", "Mandi pertama"),               // C-S6
                q.s(USER_CHECKIN, "第一次修剪指甲", "Potong kuku pertama"),           // C-S7
                q.s(USER_CHECKIN, "第一次吃零食", "Camilan pertama"),             // C-S8
                q.s(USER_CHECKIN, "第一次睡在你身边", "Tidur di sisimu pertama"),         // C-S9
                q.s(USER_CHECKIN, "第一次发出咕噜声", "Dengkuran pertama"),         // C-S10
                q.s(USER_CHECKIN, "第一次在窗边晒太阳", "Berjemur di jendela pertama"),       // C-S11
                q.s(USER_CHECKIN, "第一次玩逗猫棒", "Main tongkat pertama"),           // C-S12
                q.s(USER_CHECKIN, "第一次钻进纸箱", "Masuk kardus pertama"),           // C-S13
                q.s(SYSTEM_AUTO, "第一次被评论", "Komentar pertama"),              // C-S14
                q.s(SYSTEM_AUTO, "第一次收到点赞", "Suka pertama"),            // C-S15
                // M 级（10）
                q.m(USER_CHECKIN, "第一次出门探险", "Petualangan pertama"),           // C-M1
                q.m(USER_CHECKIN, "第一次坐车", "Naik mobil pertama"),               // C-M2
                q.m(USER_CHECKIN, "完成第一次疫苗接种", "Vaksinasi pertama"),       // C-M3
                q.m(USER_CHECKIN, "完成第一次驱虫", "Obat cacing pertama"),           // C-M4
                q.m(USER_CHECKIN, "第一次看兽医", "Ke dokter hewan pertama"),             // C-M5
                q.m(USER_CHECKIN, "第一次见到其他猫咪", "Bertemu kucing lain"),       // C-M6
                q.m(USER_CHECKIN, "学会回应自己的名字", "Kenal namanya"),       // C-M7
                q.m(SYSTEM_AUTO, "陪伴满 30 天", "30 hari bersama"),              // C-M8
                q.m(USER_CHECKIN, "完成绝育手术", "Steril selesai"),             // C-M9
                q.m(SYSTEM_AUTO, "成长日历记录满 10 条", "10 catatan tumbuh kembang"),       // C-M10
                // L 级（5）
                q.l(PUSH_PUBLISH, "第一个生日 🎂", "Ulang tahun pertama 🎂"),            // C-L1
                q.l(PUSH_PUBLISH, "陪伴满 100 天", "100 hari bersama"),            // C-L2
                q.l(PUSH_PUBLISH, "陪伴满 365 天", "365 hari bersama"),            // C-L3
                q.l(SYSTEM_AUTO, "完成全部健康里程碑", "Semua tonggak kesehatan"),         // C-L4
                q.l(SYSTEM_AUTO, "成长日历记录满 30 条", "30 catatan tumbuh kembang"),       // C-L5
                // 聚合里程碑（S 级 +1，末位 sortOrder，7.3）：6 新手任务全完成自动解锁。
                q.s(SYSTEM_AUTO, "新手毕业 · Lulus Pemula", "Lulus Pemula 🎓"));  // C-S16
    }

    // ----------------------------------------------------------------------------------------
    // 🐶 狗狗里程碑清单（共 31：S16(含 Lulus Pemula) / M10 / L5）
    // ----------------------------------------------------------------------------------------
    private static List<MilestoneDefinition> buildDog() {
        Seq q = new Seq("D");
        return List.of(
                // S 级（15）
                q.s(SYSTEM_AUTO, "宠物档案创建完成", "Profil dibuat"),          // D-S1
                q.s(SYSTEM_AUTO, "第一张照片上传到成长日历", "Foto pertama di kalender"),   // D-S2
                q.s(SYSTEM_AUTO, "第一次分享宠物名片", "Kartu pertama dibagikan"),         // D-S3
                q.s(SYSTEM_AUTO, "第一次保存兽医问诊结论", "Catatan dokter pertama"),     // D-S4
                q.s(SYSTEM_AUTO, "第一次发布日常分享", "Postingan harian pertama"),         // D-S5
                q.s(USER_CHECKIN, "第一次洗澡", "Mandi pertama"),               // D-S6
                q.s(USER_CHECKIN, "第一次美容 / 梳毛", "Perawatan bulu pertama"),         // D-S7
                q.s(USER_CHECKIN, "第一次吃零食", "Camilan pertama"),             // D-S8
                q.s(USER_CHECKIN, "第一次睡在你身边", "Tidur di sisimu pertama"),         // D-S9
                q.s(USER_CHECKIN, "第一次摇尾巴", "Kibas ekor pertama"),             // D-S10
                q.s(USER_CHECKIN, "第一次戴项圈 / 牵引绳", "Kalung & tali pertama"),     // D-S11
                q.s(USER_CHECKIN, "第一次玩球", "Main bola pertama"),               // D-S12
                q.s(USER_CHECKIN, "第一次游泳 / 玩水", "Berenang pertama"),         // D-S13
                q.s(SYSTEM_AUTO, "第一次被评论", "Komentar pertama"),              // D-S14
                q.s(SYSTEM_AUTO, "第一次收到点赞", "Suka pertama"),            // D-S15
                // M 级（10）
                q.m(USER_CHECKIN, "第一次出门散步", "Jalan-jalan pertama"),           // D-M1
                q.m(USER_CHECKIN, "第一次坐车", "Naik mobil pertama"),               // D-M2
                q.m(USER_CHECKIN, "完成第一次疫苗接种", "Vaksinasi pertama"),       // D-M3
                q.m(USER_CHECKIN, "完成第一次驱虫", "Obat cacing pertama"),           // D-M4
                q.m(USER_CHECKIN, "第一次看兽医", "Ke dokter hewan pertama"),             // D-M5
                q.m(USER_CHECKIN, "第一次见到其他狗狗", "Bertemu anjing lain"),       // D-M6
                q.m(USER_CHECKIN, "学会第一个指令", "Perintah pertama dikuasai"),           // D-M7
                q.m(SYSTEM_AUTO, "陪伴满 30 天", "30 hari bersama"),              // D-M8
                q.m(USER_CHECKIN, "完成绝育手术", "Steril selesai"),             // D-M9
                q.m(SYSTEM_AUTO, "成长日历记录满 10 条", "10 catatan tumbuh kembang"),       // D-M10
                // L 级（5）
                q.l(PUSH_PUBLISH, "第一个生日 🎂", "Ulang tahun pertama 🎂"),            // D-L1
                q.l(PUSH_PUBLISH, "陪伴满 100 天", "100 hari bersama"),            // D-L2
                q.l(PUSH_PUBLISH, "陪伴满 365 天", "365 hari bersama"),            // D-L3
                q.l(SYSTEM_AUTO, "完成全部健康里程碑", "Semua tonggak kesehatan"),         // D-L4
                q.l(SYSTEM_AUTO, "成长日历记录满 30 条", "30 catatan tumbuh kembang"),       // D-L5
                // 聚合里程碑（S 级 +1，末位 sortOrder，7.3）：6 新手任务全完成自动解锁。
                q.s(SYSTEM_AUTO, "新手毕业 · Lulus Pemula", "Lulus Pemula 🎓"));  // D-S16
    }

    // ----------------------------------------------------------------------------------------
    // 🐾 通用里程碑清单（其他宠物，共 16：S9(含 Lulus Pemula) / M4 / L3）
    // ----------------------------------------------------------------------------------------
    private static List<MilestoneDefinition> buildOther() {
        Seq q = new Seq("G");
        return List.of(
                // S 级（8）
                q.s(SYSTEM_AUTO, "宠物档案创建完成", "Profil dibuat"),          // G-S1
                q.s(SYSTEM_AUTO, "第一张照片上传到成长日历", "Foto pertama di kalender"),   // G-S2
                q.s(SYSTEM_AUTO, "第一次分享宠物名片", "Kartu pertama dibagikan"),         // G-S3
                q.s(SYSTEM_AUTO, "第一次保存兽医问诊结论", "Catatan dokter pertama"),     // G-S4
                q.s(SYSTEM_AUTO, "第一次发布日常分享", "Postingan harian pertama"),         // G-S5
                q.s(USER_CHECKIN, "第一次吃零食", "Camilan pertama"),             // G-S6
                q.s(SYSTEM_AUTO, "第一次被评论", "Komentar pertama"),              // G-S7
                q.s(SYSTEM_AUTO, "第一次收到点赞", "Suka pertama"),            // G-S8
                // M 级（4）
                q.m(USER_CHECKIN, "第一次看兽医", "Ke dokter hewan pertama"),             // G-M1
                q.m(USER_CHECKIN, "完成第一次健康检查 / 疫苗", "Cek kesehatan pertama"), // G-M2
                q.m(SYSTEM_AUTO, "陪伴满 30 天", "30 hari bersama"),              // G-M3
                q.m(SYSTEM_AUTO, "成长日历记录满 10 条", "10 catatan tumbuh kembang"),       // G-M4
                // L 级（3）
                q.l(PUSH_PUBLISH, "第一个生日 🎂", "Ulang tahun pertama 🎂"),            // G-L1
                q.l(PUSH_PUBLISH, "陪伴满 100 天", "100 hari bersama"),            // G-L2
                q.l(PUSH_PUBLISH, "陪伴满 365 天", "365 hari bersama"),            // G-L3
                // 聚合里程碑（S 级 +1，末位 sortOrder，7.3）：6 新手任务全完成自动解锁。
                q.s(SYSTEM_AUTO, "新手毕业 · Lulus Pemula", "Lulus Pemula 🎓"));  // G-S9
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

        MilestoneDefinition s(MilestoneTriggerType t, String zh, String id) {
            return new MilestoneDefinition(prefix + "-S" + (++s), S, t, ++order, zh, id);
        }

        MilestoneDefinition m(MilestoneTriggerType t, String zh, String id) {
            return new MilestoneDefinition(prefix + "-M" + (++m), M, t, ++order, zh, id);
        }

        MilestoneDefinition l(MilestoneTriggerType t, String zh, String id) {
            return new MilestoneDefinition(prefix + "-L" + (++l), L, t, ++order, zh, id);
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
