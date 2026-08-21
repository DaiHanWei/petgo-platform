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
  });

  final String title;
  final bool large;
  final List<Widget> actions;

  /// 覆盖返回按钮。`null` 时：`large == false` 显示返回箭头，`large == true` 无 leading。
  final Widget? leading;

  /// 顶栏之下的附加行（订单列表的状态 Tab 行）。高度计入 [preferredSize]。
  final PreferredSizeWidget? bottom;

  /// 栏体高度。设计稿：垂直内边距 14 上 / 12 下 + 文字行高 ≈ 48。
  static const double _barHeight = 48;

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
    return AppBar(
      backgroundColor: ShopColors.ink,
      foregroundColor: ShopColors.surface,
      elevation: 0,
      scrolledUnderElevation: 0, // 🔴 设计稿明令产品 UI 内不使用阴影；M3 滚动阴影须显式关掉
      systemOverlayStyle: SystemUiOverlayStyle.light,
      toolbarHeight: _barHeight,
      titleSpacing: hasLeading ? 0 : kShopScreenEdge,
      automaticallyImplyLeading: !large,
      leading: leading,
      title: Text(title, style: large ? ShopText.pageTitle : ShopText.navTitle),
      actions: actions,
      bottom: bottom,
    );
  }
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
    this.amountColor = ShopColors.rose,
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
