import 'package:dio/dio.dart';
import 'package:tailtopia/features/growth/data/growth_repository.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';

/// 测试用 Lv1–20 等级表（PRD 曲线）。
List<GrowthLevelDef> prdLevels() {
  const th = [0, 70, 130, 250, 500, 800, 1250, 1800, 2500, 3300, 4500, 5800, 7400, 9500, 12400, 16500, 21500,
    27200, 33800, 41300];
  return [
    for (var i = 0; i < th.length; i++)
      GrowthLevelDef(
        level: i + 1,
        threshold: th[i],
        tier: i < 4
            ? GrowthTier.newbie
            : i < 9
                ? GrowthTier.buddy
                : i < 14
                    ? GrowthTier.guardian
                    : GrowthTier.star,
      ),
  ];
}

const prdTierBenefits = [
  GrowthTierBenefits(tier: GrowthTier.newbie, fromLevel: 1, toLevel: 4, benefits: ['LEVEL_BADGE']),
  GrowthTierBenefits(
      tier: GrowthTier.buddy, fromLevel: 5, toLevel: 9, benefits: ['PET_SLOT_PLUS_ONE', 'FRAME_BASIC', 'EXCLUSIVE_GIFTS_BATCH1']),
  GrowthTierBenefits(tier: GrowthTier.guardian, fromLevel: 10, toLevel: 14, benefits: ['PET_SLOT_PLUS_ONE', 'FRAME_UPGRADED']),
  GrowthTierBenefits(
      tier: GrowthTier.star, fromLevel: 15, benefits: ['FEED_BOOST', 'FRAME_ANIMATED', 'EXCLUSIVE_GIFTS_BATCH2', 'COUPON_X']),
];

const prdRules = [
  GrowthRuleProgress(code: 'DAILY_LOGIN', points: 3, dailyCap: 1, todayCount: 1, todayEarned: 3, maxedToday: true),
  GrowthRuleProgress(code: 'POST_PUBLISHED', points: 5, dailyCap: 3, todayCount: 1, todayEarned: 5),
  GrowthRuleProgress(code: 'COMMENT_POSTED', points: 2, dailyCap: 5, todayCount: 0),
  GrowthRuleProgress(code: 'LIKE_GIVEN', points: 1, dailyCap: 5, todayCount: 3, todayEarned: 3),
  GrowthRuleProgress(code: 'PLACE_CHECKIN', points: 5, dailyCap: 3, todayCount: 0),
  GrowthRuleProgress(code: 'LIKE_RECEIVED', points: 1, capGroup: 'PASSIVE'),
  GrowthRuleProgress(code: 'COMMENT_RECEIVED', points: 3, capGroup: 'PASSIVE'),
  GrowthRuleProgress(code: 'COLLECT_RECEIVED', points: 2, capGroup: 'PASSIVE'),
  GrowthRuleProgress(code: 'MILESTONE_S', points: 5),
  GrowthRuleProgress(code: 'MILESTONE_M', points: 15),
  GrowthRuleProgress(code: 'MILESTONE_L', points: 25),
  GrowthRuleProgress(code: 'GIFT_SENT', points: 5, unitAmount: 1000),
  GrowthRuleProgress(code: 'GIFT_RECEIVED', points: 4, unitAmount: 1000),
  GrowthRuleProgress(code: 'SPEND_DIGITAL', points: 5, unitAmount: 1000),
  GrowthRuleProgress(code: 'SPEND_SHOP', points: 5, unitAmount: 10000),
];

GrowthSummary midSummary({List<GrowthLedgerEntry>? recent}) => GrowthSummary(
      participating: true,
      level: 7,
      tier: GrowthTier.buddy,
      lifetimeTotal: 1420,
      currentThreshold: 1250,
      nextLevel: 8,
      nextThreshold: 1800,
      levels: prdLevels(),
      rules: prdRules,
      passiveGroup: const GrowthPassiveGroup(dailyCap: 50, todayEarned: 18),
      tierBenefits: prdTierBenefits,
      recentLedger: recent ??
          [
            GrowthLedgerEntry(ruleCode: 'LIKE_GIVEN', delta: 1, createdAt: DateTime.now().toUtc()),
            GrowthLedgerEntry(ruleCode: 'DAILY_LOGIN', delta: 3, createdAt: DateTime.now().toUtc()),
            GrowthLedgerEntry(ruleCode: 'POST_PUBLISHED', delta: 5, detailCode: 'GROWTH_MOMENT',
                createdAt: DateTime.now().toUtc()),
          ],
    );

GrowthSummary maxSummary() => GrowthSummary(
      participating: true,
      level: 20,
      tier: GrowthTier.star,
      lifetimeTotal: 45210,
      currentThreshold: 41300,
      levels: prdLevels(),
      rules: prdRules,
      passiveGroup: const GrowthPassiveGroup(dailyCap: 50),
      tierBenefits: prdTierBenefits,
    );

/// 假仓储：按队列依次返回 / 抛出；记录每次 ledger 请求的游标。
class FakeGrowthRepository extends GrowthRepository {
  FakeGrowthRepository({this.pages = const [], this.ledgers = const []}) : super(dio: Dio());

  final List<Object> pages;
  final List<Object> ledgers;
  int pageCalls = 0;
  final List<String?> ledgerCursors = [];

  @override
  Future<GrowthSummary> fetchPage() async {
    final r = pages[pageCalls < pages.length ? pageCalls : pages.length - 1];
    pageCalls++;
    if (r is GrowthSummary) return r;
    throw r;
  }

  @override
  Future<GrowthLedgerPage> fetchLedger({String? cursor}) async {
    final i = ledgerCursors.length;
    ledgerCursors.add(cursor);
    final r = ledgers[i < ledgers.length ? i : ledgers.length - 1];
    if (r is GrowthLedgerPage) return r;
    throw r;
  }
}
