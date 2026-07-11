import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/vet_online_status.dart';
import 'vet_status_sheet.dart';

/// 兽医端共享深色顶栏（原型 V- 系列 `#2B2540`）。供工作台首页/待接单/案例/对话/我的复用。
///
/// 两种模式：
/// - dashboard：传 [greetingName] → 渲染时段问候 + 医生名 + 在线开关。
/// - 普通：传 [title] → 渲染标题（可选在线开关）。
class VetTopBar extends ConsumerWidget {
  const VetTopBar(
      {super.key, this.greetingName, this.avatarUrl, this.title, this.showOnlineToggle = false});

  /// dashboard 模式医生名（非空即问候模式，优先于 [title]）。
  final String? greetingName;

  /// dashboard 模式头像 CDN URL（运营后台上传）；null/加载失败 → 首字母占位。
  final String? avatarUrl;

  /// 普通模式标题。
  final String? title;

  /// 是否显示在线开关（接 [vetOnlineStatusProvider]）。
  final bool showOnlineToggle;

  String _greeting(AppLocalizations l10n) {
    final h = DateTime.now().hour;
    if (h < 11) return l10n.greetingMorning;
    if (h < 15) return l10n.greetingAfternoon;
    if (h < 19) return l10n.greetingEvening;
    return l10n.greetingNight;
  }

  /// 头像首字母：去掉「drh.」/「dr.」头衔前缀后取首字母大写（如「Drh. Demo」→「D」）。
  String _avatarInitial(String name) {
    final cleaned = name.replaceFirst(RegExp(r'^\s*drh?\.\s*', caseSensitive: false), '').trim();
    final base = cleaned.isNotEmpty ? cleaned : name.trim();
    return base.isEmpty ? '?' : base.substring(0, 1).toUpperCase();
  }

  /// 首字母圆（改动前的原占位；无上传头像 / 图片加载失败时回退）。
  Widget _avatarInitialCircle() {
    return Container(
      width: 36,
      height: 36,
      alignment: Alignment.center,
      decoration: const BoxDecoration(color: AppColors.vetPrimary, shape: BoxShape.circle),
      child: Text(
        _avatarInitial(greetingName!),
        style: AppTypography.title.copyWith(color: AppColors.vetOnAccent, fontSize: 14),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final availability = ref.watch(vetAvailabilityProvider);
    final isGreeting = greetingName != null;

    return Container(
      width: double.infinity,
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.xl,
            AppSpacing.md,
            AppSpacing.xl,
            AppSpacing.lg,
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (isGreeting) ...[
                      Text(
                        '${_greeting(l10n)},',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.caption.copyWith(color: AppColors.vetOnAccent.withValues(alpha: 0.7)),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        greetingName!,
                        key: const ValueKey('vetTopBarName'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.headline.copyWith(color: AppColors.vetOnAccent),
                      ),
                    ] else
                      Text(
                        title ?? '',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.title.copyWith(color: AppColors.vetOnAccent),
                      ),
                  ],
                ),
              ),
              // 在线态药丸（原型：白 10% 底圆角，状态点 + 短标签）；点击弹 V-st 状态切换抽屉。
              if (showOnlineToggle) ...[
                const SizedBox(width: AppSpacing.sm),
                _OnlinePill(availability: availability, onTap: () => showVetStatusSheet(context)),
              ],
              // 医生头像圆（仅问候模式；有上传头像用真图，否则/加载失败回退首字母）。
              if (isGreeting) ...[
                const SizedBox(width: AppSpacing.sm),
                ClipOval(
                  child: (avatarUrl != null && avatarUrl!.isNotEmpty)
                      ? Image.network(avatarUrl!,
                          width: 36,
                          height: 36,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => _avatarInitialCircle())
                      : _avatarInitialCircle(),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// 在线态药丸（原型 vet-dashboard）：白 10% 底圆角 + 状态点（在线薄荷/忙琥珀/离线灰）+ 短标签；
/// 点击弹 V-st 状态切换抽屉（不再直接切换）。
class _OnlinePill extends StatelessWidget {
  const _OnlinePill({required this.availability, required this.onTap});

  final VetAvailability availability;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (Color dot, String label) = switch (availability) {
      VetAvailability.online => (AppColors.vetPrimary, l10n.vetOnlineShort),
      VetAvailability.busy => (AppColors.triageYellow, l10n.vetBusyShort),
      VetAvailability.offline => (AppColors.vetOnAccent.withValues(alpha: 0.4), l10n.vetOfflineShort),
    };
    final dim = availability == VetAvailability.offline;
    return GestureDetector(
      key: const ValueKey('vetTopBarOnlineToggle'),
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
        decoration: BoxDecoration(
          color: AppColors.vetOnAccent.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(9999),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
            ),
            const SizedBox(width: 6),
            Text(
              label,
              style: AppTypography.caption.copyWith(
                color: AppColors.vetOnAccent.withValues(alpha: dim ? 0.7 : 1.0),
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
