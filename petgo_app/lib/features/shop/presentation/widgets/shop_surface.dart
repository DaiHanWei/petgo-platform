/// 电商板块的**页面骨架**三件：灰缝分区、墨底顶栏、底部操作条。
///
/// 这三样在 01/02 的 12 屏里每屏都出现，且都有「照抄会错、不照抄更错」的细节，
/// 因此集中在这里实现一次。逐屏各写各的必然漂移。
///
/// 🔴 本文件（及同目录其余 shop_* 组件）**只服务电商子树**，用 [ShopColors] 而非
/// `AppColors`。2026-08-19 决策：电商三色分工与全局色板语义冲突，换肤范围限定在
/// `/shop/**`、`/me/addresses/**` 与电商订单页。勿在 Diary / Health / Social / Me 引用。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../core/theme/shop_tokens.dart';
import 'shop_buttons.dart';


/// 白色内容块 + 底部 3px 灰缝。
///
/// 🔴 <b>灰缝不是「细边框」，是露出页面底色</b>。设计稿原文：区块之间用 3px 实色缝分隔，
/// **不用卡片圆角 + 外边距** ——「改成卡片间距会让每屏承载量减半」。
/// 因此本组件<b>没有 margin、没有圆角、没有阴影</b>，三者任一加回去都会破坏密度。
///
/// 用法：把若干 [ShopSection] 直接竖排在 [ShopColors.bg] 底色之上即可，
/// 缝隙由每块自己的 bottom border 让出。最后一块的缝由页面底色兜住，不必特殊处理。
class ShopSection extends StatelessWidget {
  const ShopSection({
    super.key,
    required this.child,
    this.padding = kShopBlockPadding,
    this.gutter = true,
  });

  final Widget child;

  /// 块内边距。默认 `16px 12.5px`；强调块（触发卡 / 征询卡）传 [kShopEmphasisPadding]。
  final EdgeInsets padding;

  /// 是否在底部让出灰缝。相邻两块**同属一组**（如支付方式的两条）时传 `false`，
  /// 改用块内分隔线 —— 灰缝表示「换话题」，块内线表示「同一话题的下一行」。
  final bool gutter;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: ShopColors.surface,
        border: gutter
            ? const Border(bottom: BorderSide(color: ShopColors.bg, width: kShopGutter))
            : null,
      ),
      child: Padding(padding: padding, child: child),
    );
  }
}

/// 加载失败占位 —— 一句说明 + 一个**可见的重试按钮**。
///
/// 🔴 <b>错误态必须给出口</b>（2026-08-27 审查结论）。此前电商各页的错误分支一律只画一行
/// 文案，唯一的重试途径是下拉刷新 —— 而 Android 的 `ClampingScrollPhysics` 在内容撑不满
/// 一屏时**根本拉不动**，于是「下拉重试」这句提示指向一个不存在的手势。
///
/// ⚠️ 用它替换错误占位时，如果外层还挂着 `RefreshIndicator`，记得同时把滚动容器的
/// physics 设为 [AlwaysScrollableScrollPhysics]，否则下拉那条路依旧是断的。
class ShopRetryState extends StatelessWidget {
  const ShopRetryState({
    super.key,
    required this.message,
    required this.retryLabel,
    required this.onRetry,
  });

  final String message;

  /// 由调用方传 `l10n.commonRetry` —— 本目录下的组件刻意不依赖 l10n。
  final String retryLabel;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(message, textAlign: TextAlign.center, style: ShopText.body),
              const SizedBox(height: 16),
              ShopButton(
                key: const ValueKey('shopRetryButton'),
                label: retryLabel,
                variant: ShopButtonVariant.outlinePurple,
                onTap: onRetry,
              ),
            ],
          ),
        ),
      );
}

/// 块内分隔线（1px，比灰缝弱一档）。用于「同一区块里的下一行」。
class ShopDivider extends StatelessWidget {
  const ShopDivider({super.key, this.color = ShopColors.divider, this.margin = EdgeInsets.zero});

  final Color color;
  final EdgeInsets margin;

  @override
  Widget build(BuildContext context) => Padding(
        padding: margin,
        child: SizedBox(height: 1, child: ColoredBox(color: color)),
      );
}

/// 墨底顶栏。
///
/// 两种形态由 [large] 切换：
/// - `large: false`（默认）—— 详情/结算等次级页，标题 14.5px/700 + 返回箭头
/// - `large: true` —— Toko 首页 / 订单列表这类 Tab 级页面，标题 20px/800，无返回
///
/// 🔴 <b>必须显式设 [SystemUiOverlayStyle.light]</b>：墨底之上系统默认的深色状态栏图标
/// 会看不见。Material 的 AppBar 会按 `backgroundColor` 亮度自动推断，但本栏的背景是
/// 由 [flexibleSpace] 之外的 `backgroundColor` 给的，推断结果在部分安卓 ROM 上不稳，
/// 故不依赖推断、直接指定。
class ShopAppBar extends StatelessWidget implements PreferredSizeWidget {
  const ShopAppBar({
    super.key,
    required this.title,
    this.large = false,
    this.actions = const [],
    this.leading,
    this.bottom,
    this.tone = ShopAppBarTone.dark,
    this.titleWidget,
  });

  final String title;

  /// 标题槽换成任意控件（搜索页把整条标题让给输入框）。
  ///
  /// ⚠️ 给了它就**完全接管**标题位，[title] 不再渲染 —— 但仍需传（可传空串），
  /// 它还兼作无障碍与调试时的页面名。
  final Widget? titleWidget;
  final bool large;
  final List<Widget> actions;

  /// 覆盖返回按钮。`null` 时：`large == false` 显示返回箭头，`large == true` 无 leading。
  final Widget? leading;

  /// 顶栏之下的附加行（订单列表的状态 Tab 行）。高度计入 [preferredSize]。
  final PreferredSizeWidget? bottom;

  /// 顶栏配色（2026-08-27）。
  ///
  /// 🔴 三个值必须**成组**变化：底色、前景色、状态栏图标亮暗 ——
  /// 单独改任何一个都会得到"白底白字"或"深底看不见状态栏图标"这类当场失效的组合。
  /// 所以做成一个枚举而不是三个独立参数。
  final ShopAppBarTone tone;

  /// 栏体高度。设计稿：垂直内边距 14 上 / 12 下 + 文字行高 ≈ 48。
  ///
  /// 🔴 同时以 [kShopAppBarHeight] 对外暴露：Toko 的 banner 渐变要盖住整条顶栏，
  /// 必须知道它多高。让调用方各写一个字面量 48，改这里时就会对不上。
  static const double _barHeight = kShopAppBarHeight;

  @override
  Size get preferredSize =>
      Size.fromHeight(_barHeight + (bottom?.preferredSize.height ?? 0));

  @override
  Widget build(BuildContext context) {
    // 🔴 `titleSpacing: 0` 只在**左边确实有返回箭头**时才对。
    //    没有箭头时（深链直达、或页面本就无处可返回）标题会顶死到 x=0 被屏幕边缘切掉 ——
    //    真机上「Keranjang (2)」的 K 就是这么少半个的。
    //    所以按「有没有 leading」决定：有则 0（标题紧跟箭头），无则退回屏边距。
    final hasLeading =
        leading != null || (!large && (Navigator.of(context).canPop()));
    // 🔴 底色 / 前景 / 状态栏图标必须同源于 tone，见 [tone] 的说明。
    final c = colorsOf(tone);
    return AppBar(
      backgroundColor: c.background,
      foregroundColor: c.foreground,
      elevation: 0,
      scrolledUnderElevation: 0, // 🔴 设计稿明令产品 UI 内不使用阴影；M3 滚动阴影须显式关掉
      systemOverlayStyle: c.overlay,
      toolbarHeight: _barHeight,
      titleSpacing: hasLeading ? 0 : kShopScreenEdge,
      automaticallyImplyLeading: !large,
      leading: leading,
      // 🔴 标题色必须 `copyWith` 覆盖掉（D-1）：`ShopText.pageTitle` / `navTitle`
      //    **自带 `color: ShopColors.surface`（白）**，而 TextStyle 里写死的 color
      //    压得过 `AppBar.foregroundColor`。tone 切到 light（白底）时，
      //    不覆盖就是白底白字 —— 元素在控件树里、用户一个字看不见。
      title: titleWidget ??
          Text(title,
              style: (large ? ShopText.pageTitle : ShopText.navTitle)
                  .copyWith(color: c.foreground)),
      actions: actions,
      bottom: bottom,
    );
  }

  /// 🔴 顶栏配色的**唯一出处**。放在 actions 里的胶囊（PawCoin 余额、购物车）
  /// 必须从这里取色，**不能各自写死白色**。
  ///
  /// D-1（2026-09-02 stag 电商测试，P1）就是这么来的：tone 切到 [ShopAppBarTone.light]
  /// （白底，Toko 无 banner 时的**设计内空态**）之后，标题与两个胶囊仍是白字 + 白色半透明底
  /// ⇒ 整条顶栏在截图里恒为 RGB(255,255,255)。控件树里三个元素都在、无障碍也读得到，
  /// 用户却什么都看不见 —— 连**购物车入口**都只能靠盲点角标位置。
  ///
  /// ⚠️ 白色顶栏本身不是故障（后台 banner 页写明了「这是设计内的空态」）。
  /// 缺陷在于**前景色没跟着空态适配**。所以修的是「前景跟着 tone 走」，不是把底色改回深色。
  static ShopAppBarColors colorsOf(ShopAppBarTone tone) => switch (tone) {
        ShopAppBarTone.dark => (
            background: ShopColors.ink,
            foreground: ShopColors.surface,
            capsule: ShopColors.onInk12,
            overlay: SystemUiOverlayStyle.light,
          ),
        // 白底 → 前景取**主体色**（品牌紫），在白底上可见（2026-09-02 产品拍板）。
        // 胶囊底同步换成浅紫 —— onInk12 是「白 12%」，压在白底上等于没有。
        ShopAppBarTone.light => (
            background: ShopColors.surface,
            foreground: ShopColors.purple,
            capsule: ShopColors.purpleTagBg,
            overlay: SystemUiOverlayStyle.dark,
          ),
        // 透明：浮在 banner 图之上。前景仍取白色 —— 可读性由页面在图上压的那层
        // 渐变保证（见 toko_page_v2 的 _BannerHeader），不是靠这里换颜色。
        ShopAppBarTone.transparent => (
            background: Colors.transparent,
            foreground: ShopColors.surface,
            capsule: ShopColors.onInk12,
            overlay: SystemUiOverlayStyle.light,
          ),
      };
}

/// 一组顶栏配色。见 [ShopAppBar.colorsOf]。
///
/// 🔴 四个值**成组使用**，拆开取任意一个都可能拼出「白底白字」这类当场失效的组合。
typedef ShopAppBarColors = ({
  Color background,
  Color foreground,
  Color capsule,
  SystemUiOverlayStyle overlay,
});

/// [ShopAppBar] 的栏体高度（不含状态栏）。见 ShopAppBar._barHeight 的说明。
const double kShopAppBarHeight = 48;

/// [ShopAppBar] 的配色形态（2026-08-27）。
///
/// 每个值代表一整组「底色 + 前景色 + 状态栏图标亮暗」的搭配，不可拆开使用。
enum ShopAppBarTone {
  /// 深紫底 + 白字（电商各页默认）。
  dark,

  /// 白底 + 深字。Toko 在**没有 banner** 时用它 ——
  /// 产品要求此时顶部与其他板块的深色顶栏区分开。
  light,

  /// 完全透明，浮在 banner 图之上。图要顶到屏幕最上沿、不留纯色条时用。
  transparent,
}

/// 底部操作条 —— 形态 A：**左总价 + 右按钮**（购物车、结算、待支付详情）。
///
/// 🔴 安全区：设计稿写的是 `padding: 10px 14px 18px`，末值 18 是**给安全区留的视觉量**，
/// 不是真实内边距。这里改用 `viewPadding.bottom` —— 写死 18 在全面屏上会被 home 指示条压住，
/// 在无刘海设备上又白留一截。
class ShopBottomBarWithTotal extends StatelessWidget {
  const ShopBottomBarWithTotal({
    super.key,
    required this.label,
    required this.amount,
    required this.action,
    this.amountColor = ShopColors.accent,
  });

  /// 总价上方的小灰标签（`Total · 2 barang` / `Total bayar`）。
  final String label;

  /// 已格式化好的金额串。**本组件不做格式化** —— 货币格式是 l10n 的事。
  final String amount;

  /// 右侧主按钮。
  final Widget action;

  /// 🔴 金额颜色按状态传：未付款玫红、已付款 [ShopColors.ink]、
  /// 超服务范围/售罄 [ShopColors.text4] 灰。默认玫红是最常见的「待付款」态。
  final Color amountColor;

  @override
  Widget build(BuildContext context) {
    return _BottomBarShell(
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(label, style: ShopText.meta.copyWith(fontSize: 9.5)),
                const SizedBox(height: 1),
                Text(amount, style: ShopText.priceTotal.copyWith(color: amountColor)),
              ],
            ),
          ),
          const SizedBox(width: 12),
          action,
        ],
      ),
    );
  }
}

/// 底部操作条 —— 形态 B：**等宽双按钮**（商品详情、退款方式、已发货详情）。
///
/// [secondaryFlex] / [primaryFlex] 默认 1:1；设计稿在个别屏用 1:1.4（订单列表卡的
/// `Bayar Sekarang`）或 1:1.6（Diary 触发卡的 `Beli Lagi`）—— 主操作更宽是刻意的权重表达。
class ShopBottomBarActions extends StatelessWidget {
  const ShopBottomBarActions({
    super.key,
    required this.primary,
    this.secondary,
    this.secondaryFlex = 1,
    this.primaryFlex = 1,
  });

  final Widget primary;
  final Widget? secondary;
  final int secondaryFlex;
  final int primaryFlex;

  @override
  Widget build(BuildContext context) {
    return _BottomBarShell(
      child: Row(
        children: [
          if (secondary != null) ...[
            Expanded(flex: secondaryFlex, child: secondary!),
            const SizedBox(width: 9),
          ],
          Expanded(flex: primaryFlex, child: primary),
        ],
      ),
    );
  }
}

/// 两种底部条共用的外壳：白底 + 1px 上边线 + 安全区内边距。
class _BottomBarShell extends StatelessWidget {
  const _BottomBarShell({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    // viewPadding 而非 padding：底部条本身就贴着屏幕底，此处要的是「物理安全区」，
    // 而 padding 在键盘弹出时会被 viewInsets 吃掉，导致按钮短暂贴边。
    final safeBottom = MediaQuery.viewPaddingOf(context).bottom;
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: ShopColors.surface,
        border: Border(top: BorderSide(color: ShopColors.border2)),
      ),
      child: Padding(
        padding: kShopBottomBarPadding.copyWith(bottom: kShopBottomBarPadding.bottom + safeBottom),
        child: child,
      ),
    );
  }
}
