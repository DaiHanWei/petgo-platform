import '../../../l10n/app_localizations.dart';
import 'pet_profile.dart';
import 'timeline_item.dart';

/// Diary 游客引导态的**内置示例数据**（V1.1.2 Story 2.2 · FR-80 · AD-13 · NFR-5）。
///
/// 权威来源：**UI 稿 A1 屏**（9 条 · 示例宠物猫 Mochi）。PRD FR-80 的示例表为 7-23 初稿，
/// 内容已与 A1 分叉，以 A1 为准。
///
/// 三条硬约束：
/// 1. **零网络**：文案走 ARB 双语体系，配图走 `assets/demo_diary/`（`asset:` 前缀），
///    飞行模式下必须完整渲染 —— 这是 App 门面第一屏，白屏代价极高。
/// 2. **形状与真实条目一致**：示例即 [TimelineItem]，携带同样的 `itemType` / `milestoneCode` 等字段，
///    因此能直接喂给 `TimelineItemTile`（与真实时间线同一组件，NFR-7）。
/// 3. **明确标示为示例**：页面顶部「✨ Contoh」条由页面负责渲染。
///
/// ⚠️ 占位素材（OQ-1 未闭合）：`assets/demo_diary/demo_diary_*.jpg` 目前是从 `assets/seed/` 取的
/// 通用宠物照缩图占位。**素材到位后只替换同名文件即可，不需改结构**（命名前缀 `demo_diary_` 便于检索）。
class DiaryDemoData {
  const DiaryDemoData._();

  /// 示例宠物档案（A1：Mochi · Kucing · Domestik · 1 tahun 2 bulan · 个性签名）。
  ///
  /// [now] 供测试固定时间；生日按「当前时间往前 14 个月」构造，使年龄恒显示「1 tahun 2 bulan」
  /// （不会随时间漂移成「3 tahun」这种与稿子不符的值）。
  static PetProfile profile(AppLocalizations l10n, {DateTime? now}) {
    final anchor = now ?? DateTime.now();
    return PetProfile(
      // id/cardToken 为示例占位：示例态不发任何请求，这两个值不会被用于寻址。
      id: 0,
      name: l10n.diaryDemoPetName,
      cardToken: '',
      petType: 'CAT',
      avatarUrl: 'asset:assets/demo_diary/demo_diary_avatar.jpg',
      breed: l10n.diaryDemoPetBreed,
      birthday: DateTime(anchor.year - 1, anchor.month - 2, anchor.day),
      intro: l10n.diaryDemoPetIntro,
    );
  }

  /// 示例统计（A1 页头：14 Momen / 3 Konsultasi / 9 Pencapaian，进度 9/30）。
  static const int happyCount = 14;
  static const int consultCount = 3;
  static const int milestoneCompleted = 9;
  static const int milestoneTotal = 30;

  /// A1 的 9 条示例条目，**倒序**（与真实时间线同序）。
  ///
  /// 日期锚定「今天」往前推 A1 的相对间隔（0/4/8/10/13/16/20/26/27 天），
  /// 让示例本看起来是「最近的记录」而非某个固定的历史月份。
  static List<TimelineItem> items(AppLocalizations l10n, {DateTime? now}) {
    final today = now ?? DateTime.now();
    DateTime at(int daysAgo) {
      final d = DateTime(today.year, today.month, today.day).subtract(Duration(days: daysAgo));
      return DateTime.utc(d.year, d.month, d.day, 9);
    }

    return [
      // ① 28 Mei · 类③ banner L：100 天纪念（含分享卡提示，由庆祝文案承载）
      TimelineItem(
        kind: TimelineKind.unknown,
        itemType: TimelineItemType.milestoneBanner,
        date: at(0),
        milestoneCode: 'C-L2',
        milestoneLevel: 'L',
      ),
      // ② 24 Mei · 类⑤ 身份证解锁（编号 #00842）
      TimelineItem(
        kind: TimelineKind.unknown,
        itemType: TimelineItemType.idCardIssued,
        date: at(4),
        idCardSerial: '#00842',
      ),
      // ③ 20 Mei · 类② 照片卡 + 🏆 徽章（2 foto，缩略图只显首图）
      //   徽章名取里程碑 code 的本地化短名。A1 稿定的徽章文案是「Berenang pertama」(first swim)，
      //   该 code 只存在于狗系列（D-S13）——示例宠物是猫，但徽章文案由稿子拍板，故此处刻意用 D-S13。
      //   用户只看到文案、看不到 code；改用猫系列 code 会导致徽章文案与稿子不符。
      TimelineItem(
        kind: TimelineKind.happyMoment,
        itemType: TimelineItemType.happyMomentMilestone,
        date: at(8),
        text: l10n.diaryDemoItemSwim,
        imageUrls: const [
          'asset:assets/demo_diary/demo_diary_swim_1.jpg',
          'asset:assets/demo_diary/demo_diary_swim_2.jpg',
        ],
        milestoneCode: 'D-S13',
      ),
      // ④ 18 Mei · 类③ banner M：30 天
      TimelineItem(
        kind: TimelineKind.unknown,
        itemType: TimelineItemType.milestoneBanner,
        date: at(10),
        milestoneCode: 'C-M8',
        milestoneLevel: 'M',
      ),
      // ⑤ 15 Mei · 类④b 结构化健康记录胶囊（疫苗）
      TimelineItem(
        kind: TimelineKind.unknown,
        itemType: TimelineItemType.healthRecord,
        date: at(13),
        healthRecordType: 'VACCINE',
        text: l10n.diaryDemoItemVaccine,
      ),
      // ⑥ 12 Mei · 类④a 问诊存档（绿色分诊）
      TimelineItem(
        kind: TimelineKind.healthEvent,
        itemType: TimelineItemType.healthRecord,
        date: at(16),
        healthRecordType: 'CONSULT',
        sourceType: 'AI_TRIAGE',
        aiLevel: 'GREEN',
        symptomSummary: l10n.diaryDemoItemConsultSummary,
      ),
      // ⑦ 08 Mei · 类① 普通照片卡
      TimelineItem(
        kind: TimelineKind.happyMoment,
        itemType: TimelineItemType.happyMoment,
        date: at(20),
        text: l10n.diaryDemoItemNight,
        imageUrls: const ['asset:assets/demo_diary/demo_diary_night.jpg'],
      ),
      // ⑧ 02 Mei · 类① 普通照片卡（最旧的快乐时刻 → debut 🌟，由页面传 firstLabel）
      TimelineItem(
        kind: TimelineKind.happyMoment,
        itemType: TimelineItemType.happyMoment,
        date: at(26),
        text: l10n.diaryDemoItemBalcony,
        imageUrls: const ['asset:assets/demo_diary/demo_diary_balcony.jpg'],
      ),
      // ⑨ 01 Mei · 类③ banner S：建档
      TimelineItem(
        kind: TimelineKind.unknown,
        itemType: TimelineItemType.milestoneBanner,
        date: at(27),
        milestoneCode: 'C-S1',
        milestoneLevel: 'S',
      ),
    ];
  }

  /// 可无登录查看详情的三条（AC8）：只有带图的内容条目可进详情。
  /// 其余 6 条（banner ×3 / 胶囊 ×2 / 证件卡 ×1）点击一律触发建档引导 ——
  /// 它们在真实时间线里的跳转目标（里程碑列表 / 健康记录 / 身份证页）**都是受控页**，
  /// 游客点进去会在种草页中间撞上登录框。
  static bool hasDemoDetail(TimelineItem item) =>
      item.imageUrls.isNotEmpty &&
      (item.resolvedType == TimelineItemType.happyMoment ||
          item.resolvedType == TimelineItemType.happyMomentMilestone);

  /// 示例详情的正文（比时间线标题长一点，让详情页看起来像真的一条内容）。
  /// 取内置常量，**零网络**（NFR-5 延续到详情层）。
  static String detailBody(AppLocalizations l10n, TimelineItem item) {
    if (item.text == l10n.diaryDemoItemSwim) return l10n.diaryDemoBodySwim;
    if (item.text == l10n.diaryDemoItemNight) return l10n.diaryDemoBodyNight;
    return l10n.diaryDemoBodyBalcony;
  }

  /// 示例详情的互动计数（观感用；点击任一互动按钮都触发建档引导，不执行真实互动）。
  static const int detailLikeCount = 24;
  static const int detailCommentCount = 3;
}
