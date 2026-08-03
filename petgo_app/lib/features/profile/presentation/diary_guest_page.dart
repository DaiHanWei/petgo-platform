import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_guard.dart';
import '../domain/diary_demo_data.dart';
import '../domain/timeline_item.dart';
import 'diary_demo_detail_page.dart';
import 'widgets/diary_header.dart';
import 'widgets/timeline_item_tile.dart';

/// Diary 游客引导态（V1.1.2 Story 2.2 · FR-80 · UI 稿 A1）。
///
/// 未登录用户落在 Diary 时看到的**示例成长本**：明确标示为示例（✨ Contoh）、完整页头、
/// 混排五类条目的 Hero 示例时间线、情感化标题 + 一句话说明、**唯一**常驻主 CTA。
///
/// 定位是**种草页而非登录墙**：主 CTA 文案不出现「登录」字样，页面内不含「已有账号？登录」次要入口，
/// 也不触发任何滚动式 / 弹层式登录推荐（FR-79：Discovery 的 FR-0B 第 3 页触发是另一套机制，互不依赖）。
///
/// **零网络**（NFR-5 · AD-13）：文案走 ARB，配图走内置 `assets/demo_diary/`，
/// 不调用任何需要宠物档案的接口（现有档案接口对无档案用户抛 404）。
///
/// 门控：本 Story 完成时游客仍走不到这里（放行归 Story 2.4），验收走 widget test 与
/// debug 路由 `/dev/diary-guest`。
class DiaryGuestPage extends ConsumerWidget {
  const DiaryGuestPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final profile = DiaryDemoData.profile(l10n);
    final items = DiaryDemoData.items(l10n);
    // 最旧的快乐时刻 → debut 🌟（与真实时间线同规则）。
    final debutIndex = items.lastIndexWhere((it) =>
        it.resolvedType == TimelineItemType.happyMoment ||
        it.resolvedType == TimelineItemType.happyMomentMilestone);

    return Scaffold(
      backgroundColor: AppColors.cream,
      body: Column(
        children: [
          Expanded(
            child: ListView(
              key: const ValueKey('diaryGuestScroll'),
              padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 50, AppSpacing.lg, AppSpacing.lg),
              children: [
                _demoStrip(l10n),
                const SizedBox(height: 14),
                // 页头与真实态同一组件；入口均引到建档引导（不得引向受控子页）。
                DiaryHeader(
                  profile: profile,
                  happyCount: DiaryDemoData.happyCount,
                  consultCount: DiaryDemoData.consultCount,
                  milestoneCompleted: DiaryDemoData.milestoneCompleted,
                  milestoneTotal: DiaryDemoData.milestoneTotal,
                  onEditProfile: () => _startCreateProfile(context, ref),
                  onOpenIdCard: () => _startCreateProfile(context, ref),
                  onOpenHealth: () => _startCreateProfile(context, ref),
                  onOpenMilestones: () => _startCreateProfile(context, ref),
                ),
                for (var i = 0; i < items.length; i++)
                  TimelineItemTile(
                    item: items[i],
                    petName: profile.name,
                    thumbIndex: i,
                    firstLabel: i == debutIndex ? l10n.growthFirstHappyMoment : null,
                    // 带图内容 → 示例详情（无需登录，零网络）；其余 6 条 → 建档引导。
                    onTap: DiaryDemoData.hasDemoDetail(items[i])
                        ? () => _openDemoDetail(context, items[i])
                        : () => _startCreateProfile(context, ref),
                    // 类② 金徽章：真实态跳里程碑列表（受控页），游客态一律建档引导。
                    onBadgeTap: () => _startCreateProfile(context, ref),
                  ),
                const SizedBox(height: 10),
                _emotionalBlock(l10n),
              ],
            ),
          ),
          _stickyCta(context, ref, l10n),
        ],
      ),
    );
  }

  /// 「✨ Contoh」示例标示条（A1 顶部）：明确告知这不是用户自己的数据。
  Widget _demoStrip(AppLocalizations l10n) => Container(
        key: const ValueKey('diaryDemoStrip'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 3),
              decoration: BoxDecoration(
                  color: AppColors.card, borderRadius: BorderRadius.circular(999)),
              child: Text('✨ ${l10n.diaryDemoChip}',
                  style: const TextStyle(
                      fontSize: 10, fontWeight: FontWeight.w700, color: AppColors.mint)),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(l10n.diaryDemoStripLine,
                  style: const TextStyle(fontSize: 11, height: 1.4, color: AppColors.ink2)),
            ),
          ],
        ),
      );

  /// 情感化标题 + 一句话说明（AC1）。不含任何登录字样。
  Widget _emotionalBlock(AppLocalizations l10n) => Padding(
        key: const ValueKey('diaryGuestPitch'),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
        child: Column(
          children: [
            Text(l10n.diaryGuestPitchTitle,
                textAlign: TextAlign.center,
                style: const TextStyle(
                    fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 8),
            Text(l10n.diaryGuestPitchBody,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.ink2)),
          ],
        ),
      );

  /// 常驻主 CTA（A1 sticky-cta）：全页**唯一**登录引导承担者。
  Widget _stickyCta(BuildContext context, WidgetRef ref, AppLocalizations l10n) => Container(
        decoration: const BoxDecoration(
          color: AppColors.card,
          border: Border(top: BorderSide(color: AppColors.line2)),
        ),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        child: SizedBox(
          width: double.infinity,
          child: FilledButton(
            key: const ValueKey('diaryGuestPrimaryCta'),
            onPressed: () => _startCreateProfile(context, ref),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
            child: Text(l10n.diaryGuestPrimaryCta,
                style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600)),
          ),
        ),
      );

  /// 建档引导（FR-0G）。走**单一门控入口** [requireLogin]：游客弹强登录引导并注入登录后回跳建档，
  /// 已登录（debug 路由直达时可能出现）直接进建档表单。页面内所有引导点都收口到这一个函数，
  /// 避免出现第二套跳转逻辑。
  void _startCreateProfile(BuildContext context, WidgetRef ref) {
    requireLogin(
      ref,
      context,
      pendingAction: const RouteIntent(location: '/profile/create'),
      onAllowed: () => context.push('/profile/create'),
    );
  }

  /// 示例内容详情（AC8）：**页内推入**，不新增路由 ——
  /// 挂在 `/profile/...` 会被 AD-7 门控拦截，复用 `/content/:id` 则因示例无后端 ID 而 404。
  void _openDemoDetail(BuildContext context, TimelineItem item) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => DiaryDemoDetailPage(item: item)),
    );
  }
}
