import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/shop_repurchase_repository.dart';
import '../../domain/shop_product.dart';
import '../../domain/shop_repurchase.dart';

/// Toko 首页区域①（补货提醒）与区域②（为我的宠物精选）。
///
/// 🔴 <b>FR-93 状态矩阵，逐格照做</b>：
///
/// | 状态 | 区域① | 区域② |
/// |---|---|---|
/// | 游客 | 不展示 | 不展示 |
/// | 已登录·未建档 | 不展示 | **替换为建档引导卡**（复用 FR-0G 文案） |
/// | 已登录·已建档 | 按触发展示 | 展示 |
/// | 状态 B/C（无宠物） | 不展示 | 不展示 |
///
/// 「不展示」= <b>整区不渲染、不留空标题</b>，不是渲染一个空态 ——
/// 一个写着「补货提醒」却什么都没有的标题，比没有这一区更让人困惑。
///
/// 🎨 <b>UX-DR8</b>：矩阵后两种状态无视觉稿。此处按矩阵语义实现
/// （不展示 = 真的不渲染；建档卡复用既有 FR-0G 文案与样式），<b>未自创新版式</b>。

/// 区域①：补货提醒。无触发时返回 [SizedBox.shrink]（整区不渲染）。
class RepurchaseZone extends ConsumerWidget {
  const RepurchaseZone({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(repurchaseCardsProvider);
    return async.maybeWhen(
      data: (cards) {
        if (cards.isEmpty) {
          // 🔴 整区不渲染、不留空标题。DEP-6 未到位时这是常态。
          return const SizedBox.shrink();
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionLabel(l10n.tokoRestockLabel),
            for (final c in cards) _card(context, ref, l10n, c),
          ],
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }

  Widget _card(BuildContext context, WidgetRef ref, AppLocalizations l10n, RepurchaseCard c) {
    // 曝光埋点：🔒 只带 trigger_type，不带任何 PII（宠物名/体重都不上报）
    Analytics.capture('toko_repurchase_card_shown', {'trigger_type': c.triggerType});
    return Padding(
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg, vertical: AppSpacing.xxs),
      child: Card(
        margin: EdgeInsets.zero,
        child: ListTile(
          key: ValueKey('repurchaseCard_${c.triggerId}'),
          title: Text(
              // 🔴 给估算依据而非断言：「预计 ~N 天后吃完」，不写成「已经吃完了」
              c.isOverdue
                  ? l10n.tokoRestockCardOverdue(c.productName)
                  : l10n.tokoRestockCard(c.productName, c.daysLeft),
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
          trailing: TextButton(
            key: ValueKey('repurchaseCardDismiss_${c.triggerId}'),
            onPressed: () async {
              // ⚠️ 事件名与 AC 原文（repurchase_card_dismissed）不同：本仓库的埋点命名护栏
              //    要求「模块前缀 + 动作词尾」且词尾取自受控清单（HANDOFF 硬纪律 4：
              //    不放宽规则、不塞 legacyEvents）。语义完全一致。
              Analytics.capture('toko_repurchase_card_dismiss_tapped',
                  {'trigger_type': c.triggerType});
              await ref.read(shopRepurchaseRepositoryProvider).dismiss(c.triggerId);
              ref.invalidate(repurchaseCardsProvider);
            },
            child: Text(l10n.tokoRestockDismiss),
          ),
          onTap: () {
            Analytics.capture('toko_repurchase_card_tapped',
                {'trigger_type': c.triggerType, 'product_id': c.productToken});
            // 归因：带 from=repurchase，一路落到订单行（AB-13B 的服务端权威口径）
            context.push('/shop/products/${c.productToken}?from=REPURCHASE');
          },
        ),
      ),
    );
  }
}

/// 区域②：为我的宠物精选（也用于 Diary 档案页推荐区 —— 两处同一个组件，同一套规则）。
class ProfileRecoZone extends ConsumerWidget {
  const ProfileRecoZone({super.key, this.zone = 'profile_reco'});

  /// 曝光埋点的 `zone` 取值：Toko 首页与 Diary 档案页要能分开看。
  final String zone;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(recommendationsProvider);
    return async.maybeWhen(
      data: (r) {
        // 🔴 游客 / 无宠物 → 整区不渲染（矩阵第 1、4 行）。
        //    provider 在游客态直接返回 GUEST 且【不发请求】—— 游客态零数据暴露。
        if (r.isGuest) {
          return const SizedBox.shrink();
        }
        if (r.needsProfileCreation) {
          return _createProfileCard(context, l10n);
        }
        if (r.items.isEmpty) {
          return const SizedBox.shrink();
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionLabel(l10n.tokoRecoLabel(r.petName ?? '')),
            for (final item in r.items) _item(context, item),
            // 🔴 降级态尾部的补全引导卡 —— 这是存量用户回填体重的【唯一入口】（L-9）
            if (r.needsProfileCompletion) _completeProfileCard(context, l10n, r),
          ],
        );
      },
      orElse: () => const SizedBox.shrink(),
    );
  }

  /// 🔴 已登录未建档 → **整区替换为建档引导卡**，复用 FR-0G 文案，不新建一套。
  Widget _createProfileCard(BuildContext context, AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Card(
          key: const ValueKey('tokoRecoCreateProfileCard'),
          margin: EdgeInsets.zero,
          child: ListTile(
            title: Text(l10n.tokoRecoCreateProfileTitle,
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(l10n.profileOnboardingBody),
            trailing: FilledButton(
              onPressed: () => context.push('/profile/create'),
              child: Text(l10n.tokoRecoCreateProfileCta),
            ),
          ),
        ),
      );

  /// 降级引导卡。文案是 `Lengkapi berat badan {宠物名}`，点击跳档案编辑页。
  Widget _completeProfileCard(
          BuildContext context, AppLocalizations l10n, Recommendations r) =>
      Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Card(
          key: const ValueKey('tokoRecoCompleteCard'),
          margin: EdgeInsets.zero,
          color: AppColors.mintTint,
          child: ListTile(
            title: Text(l10n.tokoRecoCompleteTitle(r.petName ?? ''),
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(l10n.tokoRecoCompleteBody,
                style: const TextStyle(fontSize: 12)),
            trailing: FilledButton(
              key: const ValueKey('tokoRecoCompleteCta'),
              onPressed: () {
                Analytics.capture('toko_profile_complete_cta_tapped');
                context.push('/profile/edit');
              },
              child: Text(l10n.tokoRecoCompleteCta),
            ),
          ),
        ),
      );

  Widget _item(BuildContext context, RecommendationItem item) {
    // 🔒 埋点带 zone 与 product_id，**不带 reco_reason 之外的任何档案信息**
    Analytics.capture('toko_product_shown', {'zone': zone});
    return Padding(
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg, vertical: AppSpacing.xxs),
      child: Card(
        margin: EdgeInsets.zero,
        child: ListTile(
          key: ValueKey('recoItem_${item.productToken}'),
          title: Text(item.name, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 🔴 推荐理由 —— 不可解释的推荐在信任驱动的产品里是负资产
              Text(item.reason,
                  key: ValueKey('recoReason_${item.productToken}'),
                  style: const TextStyle(fontSize: 12, color: AppColors.mint600)),
              Text(formatIdr(item.minPrice), style: const TextStyle(fontSize: 12)),
            ],
          ),
          onTap: () {
            // 🔒 只带 zone 与 product_id。
            // 🔴 **不带 pet_id**：单账号单宠物（L-11）下它与 distinct_id 一一对应，
            //    多一个标识只是多一条能把行为拼回个人的线索，换不来任何分析能力。
            // 🔴 **不带 reco_reason**（AC 原文要求带，此处刻意不带）：理由文本里含
            //    宠物的年龄段与体型区间，等于把档案的粗化版本送进三方分析平台 ——
            //    与 NFR-5「埋点禁带 PII」冲突。而 product_id 足以在服务端 join 回
            //    商品的 ageStage/bodySize 还原理由，分析能力一点没少。
            Analytics.capture('toko_profile_reco_tapped',
                {'zone': zone, 'product_id': item.productToken});
            context.push('/shop/products/${item.productToken}?from=PROFILE_RECO');
          },
        ),
      ),
    );
  }
}

Widget _sectionLabel(String text) => Padding(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.sm),
      child: Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
    );
