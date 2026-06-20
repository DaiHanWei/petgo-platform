import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 底部 Tab 的 4 个可导航位（中间「＋」是独立凸起按钮，不占导航分支）。
enum AppTab { home, profile, triage, me }

/// 「＋」凸起按钮直径（px）。原型 feed.html `.plusinner` = 56。
const double kAddButtonSize = 56;

/// 「＋」按钮相对 centerDocked（中心贴栏顶边、半浮）再下移的量（px）。
/// 原型 `margin-top:-14px`：仅上沿露出约 14px，而非半埋；故较 centerDocked 默认再下压。
const double kAddButtonDip = 14;

/// 底栏内容高度（不含底部安全区）。原型 `.tabbar` 83px（含 home 指示区），
/// Flutter 由 SafeArea 单独补底，故栏体取 66。
const double _kBarHeight = 66;

/// 底部 Tab Bar 外壳（FR-19 / UX-DR2，1:1 还原 feed.html `.tabbar`）。
///
/// 白底、**顶部 32 圆角 + 上沿柔阴影**；5 位：首页 / 成长档案 / [+] / 问诊 / 我的。
/// 中间「＋」为凸起悬浮按钮（[AddTabButton]，Scaffold centerDocked + [kAddButtonDip] 下压，仅露上沿）。
/// **选中态（pop-art）**：紫色实心图标 + 红色错位投影 + 紫色加粗标签（非圆底）；
/// 未选：ink@55% 描边图标 + 弱色标签。
class BottomTabBar extends StatelessWidget {
  const BottomTabBar({
    super.key,
    required this.currentIndex,
    required this.onTabSelected,
  });

  /// 当前 active 导航位（0..3，对应 [AppTab.values]）。
  final int currentIndex;
  final ValueChanged<int> onTabSelected;

  /// 底部栏强调色（active 图标/标签 + 「＋」按钮统一紫，原型 #845EC9）。
  /// 保留 index 形参以兼容调用点，当前所有位返回同一色。
  static Color regionColorForTab(int index) => AppColors.accentGrowth;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DecoratedBox(
      // 白底 + 顶部 32 圆角 + 上沿柔阴影（原型 box-shadow:0 -4px 20px rgba(22,34,51,.08)）。
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(32)),
        boxShadow: [
          BoxShadow(color: Color(0x14162233), blurRadius: 20, offset: Offset(0, -4)),
        ],
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: _kBarHeight,
          child: Row(
            children: [
              _item(AppTab.home, _kIconHome, l10n.tabHome),
              _item(AppTab.profile, _kIconBook, l10n.tabProfile),
              const Expanded(child: SizedBox()), // 「＋」凸起按钮的缺口占位
              _item(AppTab.triage, _kIconSteth, l10n.tabTriage),
              _item(AppTab.me, _kIconPerson, l10n.tabMe),
            ],
          ),
        ),
      ),
    );
  }

  Widget _item(AppTab tab, _TabIcon icon, String label) {
    final bool active = currentIndex == tab.index;
    return Expanded(
      child: InkResponse(
        onTap: () => onTabSelected(tab.index),
        child: _TabItem(icon: icon, label: label, active: active),
      ),
    );
  }
}

/// Tab 图标（原型 feed.html SVG）：`outline`=描边线性（未选），`fill`=实心（选中/错位影）。
class _TabIcon {
  const _TabIcon(this.outline, this.fill);

  final String outline;
  final String fill;

  static const _head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"';

  /// 实心填充（选中主层=紫，错位影层=红）。
  String fillSvg(String hex) => '$_head fill="$hex">$fill</svg>';

  /// 描边线性（未选）；原型 `.iout` = text-primary @ .55。
  String outlineSvg(String hex, double opacity) => '$_head fill="none" stroke="$hex" '
      'stroke-opacity="$opacity" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">$outline</svg>';
}

// 原型 feed.html 各 tab 的 iout(描边)/ifill(实心) path。
const _kIconHome = _TabIcon(
  '<path d="M3 10.5L12 3l9 7.5V20a1 1 0 01-1 1H5a1 1 0 01-1-1v-9.5z"/><path d="M9 21V14h6v7"/>',
  '<path d="M12 2.5L3 9.7V21h6v-6h6v6h6V9.7L12 2.5z"/>',
);
const _kIconBook = _TabIcon(
  '<path d="M2 4h7a3 3 0 013 3v13a2 2 0 00-2-2H2V4z"/><path d="M22 4h-7a3 3 0 00-3 3v13a2 2 0 012-2h8V4z"/>',
  '<path d="M2 4h7a3 3 0 013 3v13a2 2 0 00-2-2H2V4z"/><path d="M22 4h-7a3 3 0 00-3 3v13a2 2 0 012-2h8V4z"/>',
);
const _kIconSteth = _TabIcon(
  '<circle cx="17" cy="17" r="3"/><path d="M14 17H9a6 6 0 01-6-6V6"/><path d="M7 3v5a3 3 0 006 0V3"/>',
  '<circle cx="17" cy="17" r="3.5"/><path d="M8 3a2 2 0 00-2 2v4a6 6 0 0012 0V5a2 2 0 10-4 0v4a2 2 0 01-4 0V5a2 2 0 00-2-2z"/>',
);
const _kIconPerson = _TabIcon(
  '<circle cx="12" cy="7" r="4"/><path d="M4 21a8 8 0 0116 0"/>',
  '<circle cx="12" cy="7" r="5"/><path d="M3.5 22a9 9 0 0117 0H3.5z"/>',
);

/// 「＋」细线 plus（原型 feed.html plusinner：stroke 2.5、27px、白）。
const _kIconPlus =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
    'stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>';

// 选中紫 / 错位影红 / 未选 ink（与 AppColors 同值，SVG 用 hex）。
const String _kViolet = '#845EC9'; // AppColors.mint / accentGrowth
const String _kPopRed = '#F0425A'; // AppColors.popRed
const String _kInk = '#2E2A45'; // AppColors.ink

class _TabItem extends StatelessWidget {
  const _TabItem({required this.icon, required this.label, required this.active});

  final _TabIcon icon;
  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        // 图标 26（原型 istack 30 / svg 26）；选中=紫实心 + 红错位影（pop-art）。
        SizedBox(
          width: 30,
          height: 30,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              if (active)
                Transform.translate(
                  offset: const Offset(3, 3),
                  child: SvgPicture.string(icon.fillSvg(_kPopRed), width: 26, height: 26),
                ),
              SvgPicture.string(
                key: active ? const ValueKey('activeTabIcon') : null,
                active ? icon.fillSvg(_kViolet) : icon.outlineSvg(_kInk, 0.55),
                width: 26,
                height: 26,
              ),
            ],
          ),
        ),
        const SizedBox(height: 3),
        Text(
          label,
          style: AppTypography.tabLabel.copyWith(
            color: active ? AppColors.mint : AppColors.textTertiary,
            fontWeight: active ? FontWeight.w600 : FontWeight.w500,
          ),
        ),
      ],
    );
  }
}

/// 「＋」凸起悬浮按钮（centerDocked + [kAddButtonDip] 下压，仅露上沿，原型 plusinner）。
///
/// [kAddButtonSize] 紫圆、**无描边**、紫色柔阴影（原型 `0 8px 20px rgba(132,94,201,.30)`）；
/// 细线白「＋」。
class AddTabButton extends StatelessWidget {
  const AddTabButton({super.key, required this.activeIndex, required this.onPressed});

  final int activeIndex;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final Color color = BottomTabBar.regionColorForTab(activeIndex);
    return Transform.translate(
      offset: const Offset(0, kAddButtonDip),
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
              boxShadow: [
                BoxShadow(
                    color: AppColors.mint.withValues(alpha: 0.30),
                    blurRadius: 20,
                    offset: const Offset(0, 8)),
              ],
            ),
            child: Center(child: SvgPicture.string(_kIconPlus, width: 27, height: 27)),
          ),
        ),
      ),
    );
  }
}
