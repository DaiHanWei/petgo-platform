import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/profile_repository.dart';

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
}

/// 「Know Your Pet / Kenali Hewanmu」聚合页（FR-65 · AD-A17）。
///
/// ## 🔴 本批次只有两张卡，不预埋任何第三张
/// 护照（FR-120）与 Tailsonality（FR-117）**不占位、不置灰、不出现**。
/// 批次 C 上线时它们是**新增卡**，不是"解锁已有占位" —— 所以这里连一个隐藏卡位、
/// 一个 `enabled: false` 的常量都不许留。一个灰着的「即将推出」就是一句不兑现的承诺。
///
/// ## 非猫狗：原地置灰，不新开页
/// 年龄换算只有猫狗有公认的换算标准（AD-A18）。其余物种**把年龄卡就地置灰 + 换副文案**，
/// 点击无任何反应 —— 不跳转、不弹层、不新开「不支持」页，与站内既有
/// 「功能对当前项不适用」的做法一致（AD-A17.4）。身份证对全物种可用。
class PetInsightsPage extends ConsumerWidget {
  const PetInsightsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    // 物种只用来决定年龄卡灰不灰。取不到档案（加载中/失败）时按「不是猫狗」保守处理 ——
    // 让一个算不出结果的入口可点，比它暂时灰着更糟。
    final petType = ref.watch(petProfileProvider).asData?.value?.petType;
    final bool ageCardEnabled = petType == 'CAT' || petType == 'DOG';

    return Scaffold(
      backgroundColor: AppColors.cream2,
      // 页面标题与入口卡标题**同源**（同一个 key），改名时不会只改一处（AC2）。
      appBar: AppBar(title: Text(l10n.petInsightsTitle)),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: GridView.count(
            crossAxisCount: 2,
            mainAxisSpacing: 12,
            crossAxisSpacing: 12,
            childAspectRatio: 0.95,
            shrinkWrap: true,
            children: [
              _InsightCard(
                cardKey: const ValueKey('insightIdCard'),
                icon: Icons.badge_outlined,
                title: l10n.idCardTitle,
                // 副文案沿用现成 key，不新写（AC1）。
                sub: l10n.timelineIdCardTapToView,
                onTap: () => context.push(PetInsightsRoutes.idCard),
              ),
              _InsightCard(
                cardKey: const ValueKey('insightAgeCard'),
                icon: Icons.cake_outlined,
                title: l10n.ageCardTitle,
                // 置灰态换掉召唤语：用**正面陈述适用范围**，不用否定式
                // （「不支持」听起来像故障），也禁用「即将推出」——这批明确不做。
                sub: ageCardEnabled
                    ? l10n.timelineIdCardTapToView
                    : l10n.ageCardUnavailableForSpecies,
                // 🔴 置灰即**彻底不可点**：onTap 为 null，连水波纹都不会有。
                onTap: ageCardEnabled ? () => context.push(PetInsightsRoutes.ageCard) : null,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 聚合页的卡片。样式与档案页头部的入口卡同一套（白底 r14 + 柔阴影）。
class _InsightCard extends StatelessWidget {
  const _InsightCard({
    required this.cardKey,
    required this.icon,
    required this.title,
    required this.sub,
    required this.onTap,
  });

  final Key cardKey;
  final IconData icon;
  final String title;
  final String sub;

  /// null = 置灰不可点。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final bool disabled = onTap == null;
    return Opacity(
      opacity: disabled ? 0.55 : 1,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [
            BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
          ],
        ),
        child: Material(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(14),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: cardKey,
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(icon, size: 28, color: disabled ? AppColors.textTertiary : AppColors.mint),
                  const SizedBox(height: 10),
                  Text(
                    title,
                    style: const TextStyle(
                        fontSize: 14,
                        height: 1.25,
                        fontWeight: FontWeight.w700,
                        color: AppColors.ink),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    sub,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 11, height: 1.3, color: AppColors.textTertiary),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
