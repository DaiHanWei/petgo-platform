import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 底部 Tab 的 4 个可导航位（中间「＋」是独立凸起按钮，不占导航分支）。
///
/// **枚举顺序 == 视觉顺序 == 路由分支顺序**（Story 1.1 · AD-3）：
/// Diary(profile) → Health(triage) → [+] → Discovery(home) → Me。
/// 枚举值名沿用历史语义（`home` = 原首页 Feed，现称 Discovery；`profile` = 成长档案 Diary），
/// 本 Story 只重排顺序不改名——改名会牵动 l10n key 与大量调用点，不在范围内。
///
/// `location` **内嵌在枚举上**，取代原先与枚举并行维护的 `_tabLocations` 数组：
/// 并行数组会与枚举顺序脱节（AD-3 点名的漂移风险之一），内嵌后结构上不可能对不上。
enum AppTab {
  profile('/profile'),
  triage('/triage'),
  home('/home'),
  me('/me');

  const AppTab(this.location);

  /// 该 Tab 分支的根路由路径。
  final String location;
}

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
/// 白底、**顶部 32 圆角 + 上沿柔阴影**；5 位：Diary / Health / [+] / Discovery / 我的（Story 1.1 重排）。
/// 中间「＋」为凸起悬浮按钮（[AddTabButton]，Scaffold centerDocked + [kAddButtonDip] 下压，仅露上沿）。
/// **选中态（FR-78A 方案A 萌化）**：紫色实心 glyph + **柔和圆角高亮底** + **一处宠物特征装饰**
/// （猫耳/爪印/尾巴/项圈铃铛）+ 一次 ≤150ms 轻弹跳 + 紫色加粗标签。
/// **已替换** V1.0 pop-art 的红色 (3,3) 错位投影（二者叠加视觉过噪）。
/// 未选：ink@55% 描边图标 + 弱色标签（不变）。
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
              // 顺序与 AppTab.values 严格一致（AD-3）：Diary / Health / [+] / Discovery / Me
              _item(AppTab.profile, _kIconBook, l10n.tabProfile),
              _item(AppTab.triage, _kIconSteth, l10n.tabTriage),
              const Expanded(child: SizedBox()), // 「＋」凸起按钮的缺口占位
              _item(AppTab.home, _kIconCompass, l10n.tabHome),
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
      child: _PressableTab(
        onTap: () => onTabSelected(tab.index),
        child: _TabItem(tab: tab, icon: icon, label: label, active: active),
      ),
    );
  }
}

/// Tab 按压反馈：点击/长按按住时缩小，松开带弹性回弹（pop-art 调性；比水波纹更贴合）。
/// tab 上层无 Material 祖先，InkResponse 涟漪画不出 → 改用 scale 反馈。长按松开等同点击（切 tab）。
class _PressableTab extends StatefulWidget {
  const _PressableTab({required this.child, required this.onTap});

  final Widget child;
  final VoidCallback onTap;

  @override
  State<_PressableTab> createState() => _PressableTabState();
}

class _PressableTabState extends State<_PressableTab> {
  bool _pressed = false;

  void _set(bool v) {
    if (_pressed != v) setState(() => _pressed = v);
  }

  /// 延迟回弹：快速点击时手指瞬间抬起，若立刻复位动画来不及展开 → 保底 120ms 让按压可见。
  void _release() {
    Future<void>.delayed(const Duration(milliseconds: 120), () {
      if (mounted) _set(false);
    });
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) => _set(true),
      onTapUp: (_) => _release(),
      onTapCancel: _release,
      onTap: widget.onTap,
      onLongPressStart: (_) => _set(true),
      onLongPressEnd: (_) {
        _release();
        widget.onTap();
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 140),
        curve: Curves.easeOut,
        margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: _pressed ? AppColors.accentGrowth.withValues(alpha: 0.10) : Colors.transparent,
          borderRadius: BorderRadius.circular(14),
        ),
        child: AnimatedScale(
          scale: _pressed ? 0.82 : 1.0,
          duration: const Duration(milliseconds: 140),
          curve: Curves.easeOutBack,
          child: widget.child,
        ),
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
// Discovery（原首页）：房子 → **罗盘**（Story 1.2；T3 稿与 PRD FR-78A 均作「探索=罗盘」，
// 首页改名探索后房子语义不再成立）。简笔近似，待设计精修图标到位后整批替换。
const _kIconCompass = _TabIcon(
  '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5 5-2z"/>',
  '<path d="M12 2a10 10 0 100 20 10 10 0 000-20zm4.2 5.8l-2.4 6-6 2.4 2.4-6 6-2.4z"/>',
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

/// Tab 激活态的**宠物特征装饰**（FR-78A 方案A · T3 稿）：每 Tab 一处，叠在 glyph 右上角。
/// **不改动 glyph 本体路径**——glyph 是辨识锚点，形状必须两态一致。
/// 简笔近似，与 T3 稿同一水位；设计精修图标到位后整批替换，结构不变。
const Map<AppTab, String> _kTabCharm = <AppTab, String>{
  // 猫耳（两个小三角）
  AppTab.profile: '<path d="M2 9L4 2l5 4z"/><path d="M14 6l5-4 2 7z"/>',
  // 爪印（三趾 + 掌垫）
  AppTab.triage: '<circle cx="6" cy="7" r="2.1"/><circle cx="12" cy="5" r="2.1"/>'
      '<circle cx="18" cy="7" r="2.1"/><ellipse cx="12" cy="14.5" rx="5.2" ry="4.2"/>',
  // 尾巴（上扬弧线）
  AppTab.home: '<path d="M3 19c6 2 12-1 14-7 1-3-1-6-4-5-2 .7-2.6 3.4-.6 4.4 1.6.8 3.3-.4 3.6-2.4"/>',
  // 项圈 + 铃铛
  AppTab.me: '<path d="M4 8a8 8 0 0016 0"/><circle cx="12" cy="15.5" r="3.2"/>',
};

/// 「＋」细线 plus（原型 feed.html plusinner：stroke 2.5、27px、白）。
const _kIconPlus =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
    'stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>';

// 选中紫 / 未选 ink（与 AppColors 同值，SVG 用 hex）。错位影红已随方案A 移除。
const String _kViolet = '#845EC9'; // AppColors.mint / accentGrowth
const String _kInk = '#2E2A45'; // AppColors.ink

class _TabItem extends StatelessWidget {
  const _TabItem({
    required this.tab,
    required this.icon,
    required this.label,
    required this.active,
  });

  final AppTab tab;
  final _TabIcon icon;
  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    // icon-system.md「Reduced Motion」：duration=0，状态照常切换，仅去掉动画过程。
    final bool reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final Duration bounce = reduceMotion ? Duration.zero : AppMotion.tabCharmBounce;

    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        SizedBox(
          width: 30,
          height: 30,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              // ① 柔和圆角高亮底（方案A 取代 pop-art 红色错位投影层）
              if (active)
                Positioned.fill(
                  child: DecoratedBox(
                    key: const ValueKey('activeTabHighlight'),
                    decoration: BoxDecoration(
                      color: AppColors.accentGrowth.withValues(alpha: 0.14),
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                ),
              // glyph 本体：尺寸/形状两态一致，仅换色（辨识锚点不变）
              SvgPicture.string(
                key: ValueKey(active ? 'activeTabIcon' : 'inactiveTabIcon'),
                active ? icon.fillSvg(_kViolet) : icon.outlineSvg(_kInk, 0.55),
                width: 26,
                height: 26,
              ),
              // ② 一处宠物特征装饰 + ③ 一次轻弹跳（≤150ms）
              if (active)
                Positioned(
                  right: -5,
                  top: -5,
                  child: AnimatedScale(
                    key: const ValueKey('activeTabBounce'),
                    scale: 1,
                    duration: bounce,
                    curve: Curves.easeOutBack,
                    child: SvgPicture.string(
                      key: const ValueKey('activeTabCharm'),
                      _charmSvg(tab),
                      width: 13,
                      height: 13,
                    ),
                  ),
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

  /// 该 Tab 的宠物特征装饰 SVG（品牌紫实心，与 glyph 同色系但更小）。
  static String _charmSvg(AppTab tab) =>
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="$_kViolet">'
      '${_kTabCharm[tab] ?? ''}</svg>';
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
          // 把点击墨纹裁成圆形：否则 Android 默认 InkSparkle 在矩形包围盒内绘制，
          // 表现为按下时一闪的「方形阴影」（用户反馈）。customBorder 只裁墨水，不影响下方柔阴影。
          customBorder: const CircleBorder(),
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
