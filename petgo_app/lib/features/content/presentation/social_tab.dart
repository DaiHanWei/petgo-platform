import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// Social 页顶部两个页签（V1.3.4 following 流 · spec §2.1）。
enum SocialTab {
  discover('discover'),
  following('following');

  const SocialTab(this.wire);

  /// 埋点取值（`social_tab_viewed.tab`）。
  final String wire;
}

/// 当前页签。默认 Discover；不是用户维度数据（不登记 resetUserScopedCaches）——
/// 游客态的兜底在 [HomePage] 里按登录态折算，不靠这里清。
class SocialTabNotifier extends Notifier<SocialTab> {
  @override
  SocialTab build() => SocialTab.discover;

  /// 用户切页签（含登录后回跳落到 Following）。值变了才报 `social_tab_viewed`。
  void select(SocialTab tab) {
    if (state == tab) return;
    state = tab;
    Analytics.capture('social_tab_viewed', {'tab': tab.wire});
  }

  /// 系统复位到 Discover（登出时），**不报埋点**——不是用户在看 Discover。
  void reset() => state = SocialTab.discover;
}

final NotifierProvider<SocialTabNotifier, SocialTab> socialTabProvider =
    NotifierProvider<SocialTabNotifier, SocialTab>(SocialTabNotifier.new);

/// 头部页签栏（UI 稿 A2 / A3）：两个大字号页签，选中品牌紫 + 加粗 + 下方短下划线，未选灰。
///
/// 只负责画与回调；「游客点 Following 拉起登录」的门控在调用方（[HomePage]）。
class SocialTabBar extends StatelessWidget {
  const SocialTabBar({super.key, required this.selected, required this.onSelected});

  final SocialTab selected;
  final ValueChanged<SocialTab> onSelected;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        _TabLabel(
          key: const ValueKey('socialTab_discover'),
          label: l10n.socialTabDiscover,
          active: selected == SocialTab.discover,
          onTap: () => onSelected(SocialTab.discover),
        ),
        const SizedBox(width: 22),
        _TabLabel(
          key: const ValueKey('socialTab_following'),
          label: l10n.socialTabFollowing,
          active: selected == SocialTab.following,
          onTap: () => onSelected(SocialTab.following),
        ),
      ],
    );
  }
}

class _TabLabel extends StatelessWidget {
  const _TabLabel({super.key, required this.label, required this.active, required this.onTap});

  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      selected: active,
      button: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedDefaultTextStyle(
              duration: const Duration(milliseconds: 150),
              style: TextStyle(
                fontSize: active ? 22 : 17,
                height: 1.2,
                fontWeight: active ? FontWeight.w800 : FontWeight.w600,
                color: active ? AppColors.mint : AppColors.muted,
              ),
              child: Text(label, maxLines: 1),
            ),
            const SizedBox(height: 4),
            // 下划线常驻占位（未选透明），切换时标题基线不跳。
            Container(
              width: 18,
              height: 4,
              decoration: BoxDecoration(
                color: active ? AppColors.mint : Colors.transparent,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
