import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 底部 Tab 的 4 个可导航位（中间「＋」是独立凸起按钮，不占导航分支）。
enum AppTab { home, profile, triage, me }

/// 「＋」凸起按钮直径（px）。
const double kAddButtonSize = 44;

/// active Tab 区域色填充圆直径（px）。
const double kActiveCircleSize = 34;

/// 缺口几何（CircularNotchedRectangle）。
const double kNotchMargin = 6;

/// 底部 Tab Bar 外壳（FR-19 / UX-DR2）。
///
/// 5 位：首页 / 成长档案 / [+] / 问诊 / 我的；中间「＋」为凸起悬浮按钮（[AddTabButton]，
/// 由外层 Scaffold 以 centerDocked 落入弧形缺口）。上沿在「＋」位向下内凹成
/// [CircularNotchedRectangle] 缺口。active Tab 显示 [kActiveCircleSize] 区域色填充圆 + 白图标
/// （入场 scale 0.7→1.0）；inactive 显示 18px 图标 + 9px 标签。
class BottomTabBar extends StatelessWidget {
  const BottomTabBar({
    super.key,
    required this.currentIndex,
    required this.onTabSelected,
  });

  /// 当前 active 导航位（0..3，对应 [AppTab.values]）。
  final int currentIndex;
  final ValueChanged<int> onTabSelected;

  /// active Tab 区域色映射（「＋」颜色同此取自当前 active 位）：
  /// 问诊→莫兰迪蓝，其余（首页/成长档案/我的）→默认焦糖。
  static Color regionColorForTab(int index) =>
      index == AppTab.triage.index ? AppColors.accentConsult : AppColors.accentGrowth;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return BottomAppBar(
      color: AppColors.surface,
      elevation: 8,
      shape: const CircularNotchedRectangle(),
      notchMargin: kNotchMargin,
      padding: EdgeInsets.zero,
      child: SizedBox(
        height: 56,
        child: Row(
          children: [
            _item(AppTab.home, Icons.home_rounded, l10n.tabHome),
            _item(AppTab.profile, Icons.pets_rounded, l10n.tabProfile),
            const Expanded(child: SizedBox()), // 「＋」凸起按钮的缺口占位
            _item(AppTab.triage, Icons.healing_rounded, l10n.tabTriage),
            _item(AppTab.me, Icons.person_rounded, l10n.tabMe),
          ],
        ),
      ),
    );
  }

  Widget _item(AppTab tab, IconData icon, String label) {
    final bool active = currentIndex == tab.index;
    return Expanded(
      child: InkResponse(
        onTap: () => onTabSelected(tab.index),
        child: _TabItem(
          icon: icon,
          label: label,
          active: active,
          regionColor: regionColorForTab(tab.index),
        ),
      ),
    );
  }
}

class _TabItem extends StatelessWidget {
  const _TabItem({
    required this.icon,
    required this.label,
    required this.active,
    required this.regionColor,
  });

  final IconData icon;
  final String label;
  final bool active;
  final Color regionColor;

  @override
  Widget build(BuildContext context) {
    if (active) {
      // active：区域色填充圆 + 白图标，入场 scale 0.7→1.0（UX-DR13）。
      return Center(
        child: TweenAnimationBuilder<double>(
          key: const ValueKey('activeTabCircle'),
          duration: AppMotion.tabActiveSpring,
          curve: Curves.easeOutBack,
          tween: Tween<double>(begin: 0.7, end: 1.0),
          builder: (context, scale, child) => Transform.scale(scale: scale, child: child),
          child: Container(
            width: kActiveCircleSize,
            height: kActiveCircleSize,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: regionColor, shape: BoxShape.circle),
            child: Icon(icon, size: 18, color: AppColors.onAccent),
          ),
        ),
      );
    }
    // inactive：18px 图标 + 9px 标签。
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(icon, size: 18, color: AppColors.textTertiary),
        const SizedBox(height: 2),
        Text(label, style: AppTypography.tabLabel.copyWith(color: AppColors.textTertiary)),
      ],
    );
  }
}

/// 「＋」凸起悬浮按钮（centerDocked，落在 [BottomAppBar] 的弧形缺口内）。
///
/// 44px 圆 + 3px 白描边 + 上移约 1/3 高度；颜色随 active Tab 区域色切换。
class AddTabButton extends StatelessWidget {
  const AddTabButton({super.key, required this.activeIndex, required this.onPressed});

  final int activeIndex;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final Color color = BottomTabBar.regionColorForTab(activeIndex);
    return Transform.translate(
      offset: const Offset(0, -8), // 约 1/3 高度上移
      child: Material(
        color: Colors.transparent,
        child: InkResponse(
          onTap: onPressed,
          radius: kAddButtonSize,
          child: Container(
            width: kAddButtonSize,
            height: kAddButtonSize,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
              border: Border.all(color: AppColors.onAccent, width: 3),
              boxShadow: const [
                BoxShadow(color: Color(0x33000000), blurRadius: 6, offset: Offset(0, 2)),
              ],
            ),
            child: const Icon(Icons.add, color: AppColors.onAccent, size: 26),
          ),
        ),
      ),
    );
  }
}
