import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../core/theme/colors.dart';
import '../../core/theme/motion.dart';
import '../../core/theme/typography.dart';
import '../../l10n/app_localizations.dart';

/// 底部 Tab 的 4 个可导航位（中间「＋」是独立凸起按钮，不占导航分支）。
///
/// **枚举顺序 == 视觉顺序 == 路由分支顺序**（Story 1.1 · AD-3）：
/// Diary(profile) → Health(triage) → [+] → Social(home) → Me。
/// 枚举值名沿用历史语义（`home` = 原首页 Feed，现称 Social；`profile` = 成长档案 Diary），
/// 本 Story 只重排顺序不改名——改名会牵动 l10n key 与大量调用点，不在范围内。
///
/// `location` **内嵌在枚举上**，取代原先与枚举并行维护的 `_tabLocations` 数组：
/// 并行数组会与枚举顺序脱节（AD-3 点名的漂移风险之一），内嵌后结构上不可能对不上。
enum AppTab {
  profile('/profile', 'diary'),
  triage('/triage', 'health'),
  home('/home', 'social'),
  me('/me', 'me');

  const AppTab(this.location, this.analyticsName);

  /// 该 Tab 分支的根路由路径。
  final String location;

  /// 埋点用的**产品叫法**（Story 6.1 · 命名可读性要求）。
  ///
  /// ⚠️ 枚举名是历史包袱，与产品叫法**并不对应**：`profile` 其实是 Diary、`triage` 其实是
  /// Health、`home` 其实是 Social。直接把枚举名送进埋点，看板上会出现「进入 home」
  /// 却指的是 Social 这种读不懂的数据 —— 所以对外一律用这里的名字。
  final String analyticsName;
}

/// 「＋」凸起按钮直径（px）。原型 feed.html `.plusinner` = 56。
const double kAddButtonSize = 56;

/// 「＋」按钮相对 centerDocked（中心贴栏顶边、半浮）再下移的量（px）。
/// 原型 `margin-top:-14px`：仅上沿露出约 14px，而非半埋；故较 centerDocked 默认再下压。
const double kAddButtonDip = 14;

/// 底栏内容高度（不含底部安全区）。原型 `.tabbar` 83px（含 home 指示区），
/// Flutter 由 SafeArea 单独补底，故栏体取 66。
const double _kBarHeight = 66;

/// 激活态高亮底边长（T3 稿 44×44）。glyph 恒 26×26 居中其上。
const double _kActiveHighlightSize = 44;

/// glyph 边长（两态一致，辨识锚点）。
const double _kGlyphSize = 26;

/// 高亮底圆角（T3 稿 13）。
const double _kActiveHighlightRadius = 13;

/// 底部 Tab Bar 外壳（FR-19 / UX-DR2，1:1 还原 feed.html `.tabbar`）。
///
/// 白底、**顶部 32 圆角 + 上沿柔阴影**；5 位：Diary / Health / [+] / Social / 我的（Story 1.1 重排）。
/// 中间「＋」为凸起悬浮按钮（[AddTabButton]，Scaffold centerDocked + [kAddButtonDip] 下压，仅露上沿）。
/// **选中态（FR-78A 方案A 萌化）**：紫 glyph + **44×44 violet-tint 实底圆角高亮**（r13）+
/// **一处宠物特征装饰** + 一次 ≤150ms 轻弹跳 + 紫色加粗标签。
/// **已替换** V1.0 pop-art 的红色 (3,3) 错位投影（二者叠加视觉过噪）。
/// 未选：ink@55% 描边图标 + 弱色标签（不变）。
///
/// ⚠️ **四处装饰的位置/尺寸/配色各不相同，逐条对齐 T3 稿**（不是同一个「右上角小紫图标」——
/// 曾经那样实现过，与稿子差得很远）：
/// | Tab | 装饰 | 位置（相对 44 高亮底） | 尺寸 | 配色 |
/// |---|---|---|---|---|
/// | Diary | 猫耳 | **顶部居中**（长在书上沿） | 23×11 | 紫外耳 + **粉内耳** |
/// | Health | 爪印 | 右上 (-3,-3) | 16×16 | 紫 + 白描边发光 |
/// | Social | 尾巴 | **右下** (-4,-1) | 18×18 | 紫**描边**弧线 + 白发光 |
/// | Me | 项圈铃铛 | **居中盖在人像上** | 26×26 | **金色** + 白点铃铛 |
///
/// glyph 激活态填充方式也随稿：Diary / Social / Me 用实心，**Health 保持描边**
/// （听诊器实心会糊成一团）。
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
              // 顺序与 AppTab.values 严格一致（AD-3）：Diary / Health / [+] / Socialy / Me
              _item(AppTab.profile, l10n.tabProfile),
              _item(AppTab.triage, l10n.tabTriage),
              const Expanded(child: SizedBox()), // 「＋」凸起按钮的缺口占位
              _item(AppTab.home, l10n.tabHome),
              _item(AppTab.me, l10n.tabMe),
            ],
          ),
        ),
      ),
    );
  }

  Widget _item(AppTab tab, String label) {
    final bool active = currentIndex == tab.index;
    return Expanded(
      child: _PressableTab(
        onTap: () => onTabSelected(tab.index),
        child: _TabItem(tab: tab, label: label, active: active),
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

  /// 描边线性（未选态，以及 Health 的激活态）；原型 `.iout` = text-primary @ .55。
  String outlineSvg(String hex, double opacity, {double strokeWidth = 1.6}) =>
      '$_head fill="none" stroke="$hex" stroke-opacity="$opacity" '
      'stroke-width="$strokeWidth" stroke-linecap="round" stroke-linejoin="round">$outline</svg>';
}

// 原型 feed.html 各 tab 的 iout(描边)/ifill(实心) path。
// Social（原首页）：房子 → **罗盘**（Story 1.2；T3 稿与 PRD FR-78A 均作「探索=罗盘」，
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

/// Tab → glyph 图标表（顺序无关，按 Tab 取；渲染顺序由 [BottomTabBar.build] 的 Row 决定）。
const Map<AppTab, _TabIcon> _kTabIcons = <AppTab, _TabIcon>{
  AppTab.profile: _kIconBook,
  AppTab.triage: _kIconSteth,
  AppTab.home: _kIconCompass,
  AppTab.me: _kIconPerson,
};

/// 激活态**仍用描边**的 Tab（T3 稿：听诊器实心会糊成一团）。其余激活态为紫色实心。
const Set<AppTab> _kActiveOutlineTabs = <AppTab>{AppTab.triage};

/// 激活态 glyph SVG（紫；Health 为描边 1.8，其余实心）。
@visibleForTesting
String tabActiveGlyphSvg(AppTab tab) => _kActiveOutlineTabs.contains(tab)
    ? _kTabIcons[tab]!.outlineSvg(_kViolet, 1, strokeWidth: 1.8)
    : _kTabIcons[tab]!.fillSvg(_kViolet);

/// 未选态 glyph SVG（ink@55% 描边，两态形状一致）。
@visibleForTesting
String tabInactiveGlyphSvg(AppTab tab) => _kTabIcons[tab]!.outlineSvg(_kInk, 0.55);

/// 该 Tab 的装饰 SVG（供测试断言配色/描边——`SvgStringLoader` 不暴露源串）。
@visibleForTesting
String tabCharmSvg(AppTab tab) => _kTabCharms[tab]!.svg;

/// Tab 激活态的**宠物特征装饰**（FR-78A 方案A · T3 稿逐条对齐）。
///
/// 每 Tab 一处，**位置 / 尺寸 / 配色三者各不相同**（见 [BottomTabBar] 的对照表）。
/// **不改动 glyph 本体路径**——glyph 是辨识锚点，形状必须两态一致。
///
/// 稿子用 CSS `drop-shadow(0 0 1.6px #fff) ×2` 给爪印/尾巴描白发光；flutter_svg 不支持 filter，
/// 故用「同形状白色粗描边垫底 + 紫色本体压上」近似（观感等价：叠在 glyph 上不糊）。
/// 简笔近似与 T3 稿同一水位；设计精修图标到位后整批替换，结构不变。
class _TabCharm {
  const _TabCharm({
    required this.svg,
    required this.size,
    this.top,
    this.right,
    this.bottom,
    this.centerTop = false,
    this.overlay = false,
  });

  /// 装饰 SVG（含自带配色）。
  final String svg;

  /// 边长（宽=高，稿子里几处都是等比小图）。
  final double size;

  final double? top;
  final double? right;
  final double? bottom;

  /// 顶部居中（Diary 猫耳：长在书上沿正中，不是角落挂饰）。
  final bool centerTop;

  /// 居中整体覆盖 glyph（Me 项圈：像给人像戴上项圈）。
  final bool overlay;
}

/// 猫耳（T3：紫外耳 + 粉内耳，viewBox 30×14）。
const String _kCharmCatEars =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 14">'
    '<path d="M2 14 L0.5 1 L11 9 Z" fill="$_kViolet"/>'
    '<path d="M6 11 L5 4.5 L9.5 8.5 Z" fill="$_kEarPink"/>'
    '<path d="M28 14 L29.5 1 L19 9 Z" fill="$_kViolet"/>'
    '<path d="M24 11 L25 4.5 L20.5 8.5 Z" fill="$_kEarPink"/>'
    '</svg>';

/// 爪印（三趾 + 掌垫；白垫底近似稿子的白发光）。
const String _kCharmPaw = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    '<g fill="#FFFFFF" stroke="#FFFFFF" stroke-width="2.8">'
    '<ellipse cx="12" cy="15.5" rx="5.4" ry="4.4"/><circle cx="5.5" cy="9.5" r="2.1"/>'
    '<circle cx="11.5" cy="6.2" r="2.3"/><circle cx="17.5" cy="9.5" r="2.1"/></g>'
    '<g fill="$_kViolet">'
    '<ellipse cx="12" cy="15.5" rx="5.4" ry="4.4"/><circle cx="5.5" cy="9.5" r="2.1"/>'
    '<circle cx="11.5" cy="6.2" r="2.3"/><circle cx="17.5" cy="9.5" r="2.1"/></g>'
    '</svg>';

/// 尾巴（上扬弧线，**描边**而非填充；白粗描边垫底近似白发光）。
const String _kCharmTail = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
    'stroke-linecap="round">'
    '<path d="M4 20 C13 21 18 15 15 8 C13.7 4.7 10 4.5 8.8 7" stroke="#FFFFFF" stroke-width="5.2"/>'
    '<path d="M4 20 C13 21 18 15 15 8 C13.7 4.7 10 4.5 8.8 7" stroke="$_kViolet" stroke-width="2.6"/>'
    '</svg>';

/// 项圈 + 铃铛（**金色**，覆盖在人像下半部；铃铛描白边 + 白高光点）。
const String _kCharmCollar = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    '<path d="M7 13 Q12 16 17 13" fill="none" stroke="$_kGold" stroke-width="1.8" stroke-linecap="round"/>'
    '<circle cx="12" cy="16" r="2.1" fill="$_kGold" stroke="#FFFFFF" stroke-width="0.7"/>'
    '<circle cx="12" cy="16" r="0.6" fill="#FFFFFF"/>'
    '</svg>';

/// 四个 Tab 的装饰规格（T3 稿：位置/尺寸/配色逐条对齐）。
const Map<AppTab, _TabCharm> _kTabCharms = <AppTab, _TabCharm>{
  // 书 + 猫耳：顶部居中，压在书的上沿（top:5 相对 44 高亮底）。
  AppTab.profile: _TabCharm(svg: _kCharmCatEars, size: 23, top: 5, centerTop: true),
  // 听诊器 + 爪印：右上角外沿 (-3,-3)。
  AppTab.triage: _TabCharm(svg: _kCharmPaw, size: 16, top: -3, right: -3),
  // 罗盘 + 尾巴：**右下角** (-4,-1)。
  AppTab.home: _TabCharm(svg: _kCharmTail, size: 18, right: -4, bottom: -1),
  // 人像 + 项圈：居中整体覆盖。
  AppTab.me: _TabCharm(svg: _kCharmCollar, size: _kGlyphSize, overlay: true),
};

/// 「＋」细线 plus（原型 feed.html plusinner：stroke 2.5、27px、白）。
const _kIconPlus =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
    'stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>';

// 选中紫 / 未选 ink（与 AppColors 同值，SVG 用 hex）。错位影红已随方案A 移除。
const String _kViolet = '#845EC9'; // AppColors.mint / accentGrowth
const String _kInk = '#2E2A45'; // AppColors.ink

/// 猫耳内耳粉（T3 稿 #F7C9D9）——装饰用局部色，不进 AppColors（只此一处使用）。
const String _kEarPink = '#F7C9D9';

/// 项圈铃铛金（= AppColors.gold #F6A609）。
const String _kGold = '#F6A609';

class _TabItem extends StatelessWidget {
  const _TabItem({required this.tab, required this.label, required this.active});

  final AppTab tab;
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
          width: _kActiveHighlightSize,
          height: _kActiveHighlightSize,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              // ① 柔和圆角高亮底（T3：44×44 violet-tint **实底**、圆角 13）。
              //    方案A 取代 pop-art 红色错位投影层。
              if (active)
                Positioned.fill(
                  child: DecoratedBox(
                    key: const ValueKey('activeTabHighlight'),
                    decoration: BoxDecoration(
                      color: AppColors.mintTint,
                      borderRadius: BorderRadius.circular(_kActiveHighlightRadius),
                    ),
                  ),
                ),
              // glyph 本体：尺寸/形状两态一致，仅换色（辨识锚点不变）。
              SvgPicture.string(
                key: ValueKey(active ? 'activeTabIcon' : 'inactiveTabIcon'),
                active ? tabActiveGlyphSvg(tab) : tabInactiveGlyphSvg(tab),
                width: _kGlyphSize,
                height: _kGlyphSize,
              ),
              // ② 一处宠物特征装饰（位置/尺寸/配色按 Tab 各异）+ ③ 一次轻弹跳（≤150ms）
              if (active) _charm(bounce),
            ],
          ),
        ),
        const SizedBox(height: 3),
        // 标签**必须单行**（2026-08-04 模拟器实测）：底栏是固定 66px 高，
        // 系统字号调大后 "Kesehatan" 这类长标签会折成两行，直接把栏体撑破
        // （真机上表现为底栏冒出「BOTTOM OVERFLOWED BY 2.0 PIXELS」红条）。
        // 全局 textScaler 已 clamp 到 1.3（NFR-13），即 1.3 是**受支持状态**、不是越界输入，
        // 所以必须在这里兜住：标签额外收紧到 1.15（图标承担辨识，标签是辅助信息），
        // 并强制单行 + 省略号，两道保险都不靠「把文字删掉」。
        MediaQuery.withClampedTextScaling(
          maxScaleFactor: 1.15,
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: AppTypography.tabLabel.copyWith(
              color: active ? AppColors.mint : AppColors.textTertiary,
              fontWeight: active ? FontWeight.w600 : FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }

  /// 按 T3 规格摆放该 Tab 的装饰。三种摆法：顶部居中（猫耳）/ 角落偏移（爪印、尾巴）/ 居中覆盖（项圈）。
  Widget _charm(Duration bounce) {
    final _TabCharm spec = _kTabCharms[tab]!;
    final Widget art = AnimatedScale(
      key: const ValueKey('activeTabBounce'),
      scale: 1,
      duration: bounce,
      curve: Curves.easeOutBack,
      child: SvgPicture.string(
        spec.svg,
        key: const ValueKey('activeTabCharm'),
        width: spec.size,
        // 猫耳是扁的（viewBox 30×14），按比例给高度，避免被拉方。
        height: spec.centerTop ? spec.size * 14 / 30 : spec.size,
      ),
    );

    if (spec.overlay) return Center(child: art);
    if (spec.centerTop) {
      return Align(
        alignment: Alignment.topCenter,
        child: Padding(padding: EdgeInsets.only(top: spec.top ?? 0), child: art),
      );
    }
    return Positioned(top: spec.top, right: spec.right, bottom: spec.bottom, child: art);
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
