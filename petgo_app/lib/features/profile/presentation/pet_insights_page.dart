import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../tailsonality/data/tailsonality_repository.dart';
import '../../tailsonality/domain/tailsonality_result.dart';
import '../../tailsonality/presentation/tailsonality_retake.dart';
import '../../tailsonality/presentation/tailsonality_routes.dart';
import '../../tailsonality/presentation/widgets/tailsonality_intro_sheet.dart';
import '../../../shared/widgets/coachmark_overlay.dart';
import '../data/onboarding_mark_repository.dart';
import '../data/profile_repository.dart';
import '../domain/pet_profile.dart';
import 'widgets/insight_entry_card.dart';

/// 「Know Your Pet」聚合页的路由路径（V1.3.0 批次 A · Story 5.1 · AD-A17）。
///
/// 🔴 **必须落在 `/profile/` 前缀下**：路由表对该前缀是「默认拦截、子页自动受控」，
/// 新增子页因此**自动继承游客门控**，一行安全代码都不用写。
///
/// 🔴 **绝不能把这两条路径塞进门控的例外集合**（`_controlledExactExceptions`）。
/// 那等于为了放行一个子页把安全默认反转，踩「安全规则层只升不降不可绕过」这条红线。
/// 例外集合的三条硬约束就写在路由表那一处，改之前先去读。
class PetInsightsRoutes {
  PetInsightsRoutes._();

  /// 聚合页本身。
  static const String hub = '/profile/pet-insights';

  /// 宠物身份证（KTP）**平移后**的新路径。
  ///
  /// ⚠️ 旧路径 `/profile/id-card` 保留为重定向，**不得删除** —— 站内至少两处跳转，
  /// 外加潜在的历史通知深链，断链是硬失败（AD-A17.2）。
  static const String idCard = '$hub/id-card';

  /// 年龄换算卡。页面本身属 **Story 5.2**，本 story 只负责把入口指过去。
  static const String ageCard = '$hub/age-card';

  /// 宠物护照（V1.3.2 Story 1.2 · FR-120）。同样落在 `/profile/` 下 → 自动继承游客门控。
  static const String passport = '$hub/passport';

  /// 护照页并停在某一枚章（打卡成功「Lihat Paspor」/ 章详情回跳用）。token 找不到则停第 1 页。
  static String passportFor({String? focus}) =>
      focus == null || focus.isEmpty ? passport : '$passport?focus=${Uri.encodeQueryComponent(focus)}';

  /// B4 整页落章（V1.3.2 Story 1.3）。入参经 `extra`（`NewStampArgs`），只从打卡成功页 C2 进。
  static const String passportNewStamp = '$passport/new-stamp';

  /// B5 / B6 章详情（V1.3.2 Story 1.3）。路由模板。
  static const String passportStamp = '$passport/stamps/:placeToken';

  static String passportStampFor(String placeToken) =>
      '$passport/stamps/${Uri.encodeComponent(placeToken)}';
}

/// 聚合页卡片排布（V1.3.2 Story 1.2 · C-8 / UX-DR1 的 2+2+1 规则）：
/// 按顺序每两张一行（`IntrinsicHeight > Row(stretch) > 2×Expanded`，两卡等高），
/// 最后落单的一张**整宽**独占一行。后续 story 只往卡片列表里加一项，不必再动布局。
List<Widget> insightRows(List<Widget> cards, {double gap = 10}) {
  final rows = <Widget>[];
  for (var i = 0; i < cards.length; i += 2) {
    if (rows.isNotEmpty) rows.add(SizedBox(height: gap));
    if (i + 1 < cards.length) {
      rows.add(IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: cards[i]),
            SizedBox(width: gap),
            Expanded(child: cards[i + 1]),
          ],
        ),
      ));
    } else {
      rows.add(SizedBox(width: double.infinity, child: cards[i]));
    }
  }
  return rows;
}

/// 「Know Your Pet / Kenali Hewanmu」聚合页（FR-65 · AD-A17）。
///
/// ## 卡片列表 → 每两张一行、落单整宽（[insightRows]）
/// V1.3.2 起按 C-8 顺序 KTP / 年龄卡 / 护照 / Tailsonality / 登机牌逐个**新增**；
/// 尚未上线的卡**不占位、不置灰、不出现** —— 连一个隐藏卡位、一个 `enabled: false` 的常量都不许留。
/// 一个灰着的「即将推出」就是一句不兑现的承诺。
///
/// ## 非猫狗：原地置灰，不新开页
/// 年龄换算只有猫狗有公认的换算标准（AD-A18）。其余物种**把年龄卡就地置灰 + 换副文案**，
/// 点击无任何反应 —— 不跳转、不弹层、不新开「不支持」页，与站内既有
/// 「功能对当前项不适用」的做法一致（AD-A17.4）。身份证对全物种可用。
class PetInsightsPage extends ConsumerStatefulWidget {
  const PetInsightsPage({super.key});

  @override
  ConsumerState<PetInsightsPage> createState() => _PetInsightsPageState();
}

class _PetInsightsPageState extends ConsumerState<PetInsightsPage> {
  /// Tailsonality 卡的位置锚点，供第二次入口引导量高亮框（V1.3.2 Story 2.7）。
  final GlobalKey _tailsonalityAnchor = GlobalKey();

  /// 蒙层当前挂着的 OverlayEntry。非空 = 正在展示。
  OverlayEntry? _coachmark;

  /// 本次页面生命周期内是否已经尝试过 —— 防止 build 多次就弹多次。
  bool _coachmarkTried = false;

  @override
  void initState() {
    super.initState();
    // 首帧之后再问「弹不弹」：initState 里没有布局，量不到卡的位置。
    WidgetsBinding.instance.addPostFrameCallback((_) => _maybeShowTailsonalityCoachmark());
  }

  /// 第二次入口引导（V1.3.2 Story 2.7 · AD-16 · D-17）：指着 Tailsonality 卡说「性格测试在这里」。
  ///
  /// 弹的条件（全部成立）：
  /// 1. 标记**读取成功**（读失败不弹 —— 与第一次引导「读失败按未看过」相反：本引导不求多弹，下次再试）；
  /// 2. 当前集合不含 `tailsonality_entry`；
  /// 3. 本会话**没有刚看完第一次**：服务端快照无 `ktp_moved`、当前集合却有 = 刚在成长档案页看完 → 本次不弹，
  ///    免得连弹两层；下次冷启动再弹。老账号（快照有）与新账号（两边都没有）都会弹。
  Future<void> _maybeShowTailsonalityCoachmark() async {
    if (_coachmarkTried || _coachmark != null) return;
    _coachmarkTried = true;

    final marks = await ref.read(onboardingMarksProvider.future);
    if (!mounted) return;
    final notifier = ref.read(onboardingMarksProvider.notifier);
    if (!notifier.fetchSucceeded) return;
    if (marks.contains(kOnboardingMarkTailsonalityEntry)) return;
    if (!notifier.serverHad(kOnboardingMarkKtpMoved) && marks.contains(kOnboardingMarkKtpMoved)) return;

    // 等布局完成再量位置。
    await WidgetsBinding.instance.endOfFrame;
    // 等待期间用户可能已点开别的卡：本页不在最前就不弹（否则蒙层画在别的页面上、高亮一张看不见的卡）。
    if (!mounted || !(ModalRoute.of(context)?.isCurrent ?? true)) return;
    final box = _tailsonalityAnchor.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return; // 量不到就不弹，不画一个错位的框
    final rect = box.localToGlobal(Offset.zero) & box.size;

    final l10n = AppLocalizations.of(context);
    final entry = OverlayEntry(
      builder: (_) => CoachmarkOverlay(
        spotlight: rect,
        // 蒙层跟着锚点每帧重量（卡高随档案数据回来会变）。
        anchorKey: _tailsonalityAnchor,
        // 亮块与卡严丝合缝（描边 r14 与卡片同半径）。
        padding: 0,
        title: l10n.tailsonalityCoachmarkTitle,
        text: l10n.tailsonalityCoachmarkText,
        confirmLabel: l10n.commonGotIt,
        onDismiss: _dismissCoachmark,
      ),
    );
    _coachmark = entry;
    // rootOverlay：插到**根** Overlay，遮罩才盖得住底部 Tab 栏（同第一次引导的 2026-09-21 复审结论）。
    Overlay.of(context, rootOverlay: true).insert(entry);
  }

  /// 关闭并置位。🔴 **先关再置位**：置位失败的代价只是下次再弹一次。只置本引导自己的键，不碰 `ktp_moved`。
  void _dismissCoachmark() {
    _coachmark?.remove();
    _coachmark = null;
    unawaited(ref
        .read(onboardingMarksProvider.notifier)
        .mark(kOnboardingMarkTailsonalityEntry)
        .catchError((_) {}));
  }

  /// 聚光区是镂空的，点击会穿透到卡上：蒙层在时先按关闭处理（置位），再执行卡的正常点击 ——
  /// 不能出现「抽屉弹出来了、蒙层还盖在上面」。
  void _onTailsonalityTap() {
    if (_coachmark != null) _dismissCoachmark();
    openTailsonality(context, ref);
  }

  @override
  void dispose() {
    // 蒙层挂在 Overlay 上，不随本页的 widget 树一起拆 —— 不显式移除会留一层黑幕。
    _coachmark?.remove();
    _coachmark = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 物种只用来决定年龄卡灰不灰。取不到档案（加载中/失败）时按「不是猫狗」保守处理 ——
    // 让一个算不出结果的入口可点，比它暂时灰着更糟。
    final petType = ref.watch(petProfileProvider).asData?.value?.petType;
    final bool ageCardEnabled = petType == 'CAT' || petType == 'DOG';

    return Scaffold(
      backgroundColor: AppColors.cream,
      // 页面标题与入口卡标题**同源**（同一个 key），改名时不会只改一处（AC2）。
      appBar: AppBar(title: Text(l10n.petInsightsTitle)),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          // UI 稿 P2：**横向**矮卡两两并排（AC1「与档案页入口卡同一样式」），
          // 不是竖排高卡的网格。IntrinsicHeight 让同行两卡等高（文案两语长度不同）。
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: insightRows([
              InsightEntryCard(
                inkKey: const ValueKey('insightIdCard'),
                icon: Icons.badge_outlined,
                title: l10n.idCardTitle,
                // bug 504：KTP 创建入口专属召唤语（原复用 timelineIdCardTapToView，
                // 该键 4 处共用、不能改值，故新建键）。
                sub: l10n.idCardEntrySub,
                onTap: () => context.push(PetInsightsRoutes.idCard),
              ),
              InsightEntryCard(
                inkKey: const ValueKey('insightAgeCard'),
                icon: Icons.calendar_month_outlined,
                title: l10n.ageCardTitle,
                // 置灰态换掉召唤语：用**正面陈述适用范围**，不用否定式
                // （「不支持」听起来像故障），也禁用「即将推出」——这批明确不做。
                sub: ageCardEnabled
                    ? l10n.ageCardEntrySub // bug 503：年龄卡专属副文案
                    : l10n.ageCardUnavailableForSpecies,
                // 🔴 置灰即**彻底不可点**：onTap 为 null，连水波纹都不会有。
                onTap: ageCardEnabled ? () => context.push(PetInsightsRoutes.ageCard) : null,
              ),
              // V1.3.2 Story 1.2：宠物护照，全物种可点（第 2 行落单 → 整宽）。
              InsightEntryCard(
                inkKey: const ValueKey('insightPassport'),
                icon: Icons.menu_book_outlined,
                title: l10n.passportEntryTitle,
                sub: l10n.passportEntrySub,
                onTap: () => context.push(PetInsightsRoutes.passport),
              ),
              // V1.3.2 Story 2.3：Tailsonality，全物种可点（OTHER 走通用题套）；2+2 排布。
              KeyedSubtree(
                key: _tailsonalityAnchor,
                child: InsightEntryCard(
                  inkKey: const ValueKey('insightTailsonality'),
                  icon: Icons.psychology_alt_outlined,
                  title: l10n.tailsonalityTitle,
                  sub: l10n.tailsonalityEntrySub,
                  onTap: _onTailsonalityTap,
                ),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}

/// Tailsonality 卡的分流（V1.3.2 Story 2.3 · AC1.3 / Story 2.6 · AC4）：无结果 → 说明抽屉；有结果 → 结果列表页；
/// 列表读取失败 → 仍弹说明抽屉（不因读失败挡住入口；重测免费，误进新测试无代价）。
///
/// 读列表期间再点一次直接忽略（慢网下双击会叠两层抽屉 / 推两次结果页）。
Future<void> openTailsonality(BuildContext context, WidgetRef ref) async {
  if (_tailsonalityOpening) return;
  _tailsonalityOpening = true;
  List<TailsonalityResult> results = const [];
  PetProfile? pet;
  try {
    try {
      results = await ref.read(tailsonalityRepositoryProvider).fetchResults();
    } catch (_) {
      results = const [];
    }
    if (results.isEmpty) {
      // 档案取不到：抽屉无从确认「测的是谁」，本次点击不响应（返回重进即可重试）。
      pet = await readPetForTailsonality(ref);
    }
  } finally {
    // 抽屉 / 跳转之前就放开：抽屉的 Future 可能随页面销毁永不完成，不能让闸门跟着卡死。
    _tailsonalityOpening = false;
  }
  if (!context.mounted) return;
  if (results.isNotEmpty) {
    // Story 2.6：有结果 → 结果列表页（替换 2.3 的「进最近一次结果页」临时去向）。
    context.push(TailsonalityRoutes.results);
    return;
  }
  if (pet == null) return;
  await showTailsonalityIntroSheet(context, pet);
}

bool _tailsonalityOpening = false;
