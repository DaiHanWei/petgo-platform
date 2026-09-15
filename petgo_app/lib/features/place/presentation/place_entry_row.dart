import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import 'place_list_page.dart';

/// Sosial（首页 Feed）顶部的场所入口行（V1.3.0 batch-b1 Story 1.1 · AC7 · FR-112.4 · UX-DR1）。
///
/// 单行导航条：📍图标 + 文字 + 箭头（UI 稿 B1）。**与场所列表页同一条 story 交付** ——
/// 页面没有入口就不可达、无法验收。
///
/// <h2>布局约定</h2>
/// Epic 4 的「宠物横滑行」加在本行**之下**（Story 4.4 已落地，见 `PetRecommendationStrip`；
/// 布局已在 UI 稿统一设计）。
/// 🔴 当时**没有为它预留占位** —— 预留一个空 SizedBox 会让下一个人以为横滑行已经接好只是没数据；
/// 而它自己在「游客 / 池子为空 / 取不到」时同样整行不渲染，两边都不占位。
///
/// 🔒 **游客可点**：场所列表对游客开放（后端 GET 已放行），所以这里不做登录门控、
/// 不触发登录引导。
class PlaceEntryRow extends StatelessWidget {
  const PlaceEntryRow({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.sm),
      child: Material(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: const ValueKey('placeEntryRow'),
          onTap: () => context.push(PlaceListPage.routePath),
          child: Padding(
            // 竖向 14 + 文本行高 ≈ 48 > 44：整行本身就是热区，满足 UX-DR16。
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.md, vertical: 14),
            child: Row(
              children: [
                const Icon(Icons.place_outlined, size: 20, color: AppColors.mint),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: Text(
                    l10n.placeEntryLabel,
                    style: AppTypography.body.copyWith(fontWeight: FontWeight.w600),
                  ),
                ),
                const Icon(Icons.chevron_right_rounded, size: 20, color: AppColors.mint),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
