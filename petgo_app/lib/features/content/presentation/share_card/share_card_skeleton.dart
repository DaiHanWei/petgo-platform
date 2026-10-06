import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_qr.dart';

/// 分享卡**通用骨架**的排版度量（v1.3.2 Story 4.1 · AD-14 从 [ShareCardSkeleton] 抽出）。
///
/// 只读：帖子卡（`ShareCardTemplate`）与本批次的结果卡 / 护照卡 / 登机牌卡
/// 都从这里取排版单位与内边距 —— 同一画布上，各类卡的信息段字号与品牌段完全对齐，
/// 一批卡混着发到 Story 里才成套。
///
/// 🔴 本类的每一个数都是从帖子卡原样**搬过来**的（字节级比对过，四张导出 PNG
/// 改造前后 SHA-256 一致）。改这里等于改帖子卡，先跑 `share_card_template_test.dart`。
@immutable
class ShareCardMetrics {
  const ShareCardMetrics(this.canvas);

  final CardCanvas canvas;

  /// 🔴 **品牌段固定 15%**（产品 2026-08-28 定稿：图片 65% / 作者+正文 20% / 品牌 15%）。
  ///
  /// <h2>这一版的取舍（产品 2026-08-28 二次拍板）</h2>
  /// 分两半看：
  /// - **品牌段固定** 15%（1:1 上被二维码底线抬高，见 [brandBand]）——
  ///   一批卡混着发到 Story 里**成套**靠的就是这条底边一样高。
  /// - **上面 85% 按内容伸缩**，只夹上下界（[contentMinOfRest] / [contentMaxOfRest]）。
  ///
  /// 🔴 这是对同日早些时候「三段全固定」的**修正**，不是把它推翻重来：
  /// 全固定确实让每张卡一样高，但代价是一行短文案时内容段白掉约 100px ——
  /// 而那 100px 本该给图片。产品看过实机后判定「白一条」比「图文比例略有差异」更刺眼，
  /// 于是只保留真正决定成套感的那一段（品牌段）固定。
  ///
  /// ⚠️ 因此**图片段不再是一个可以写死断言的数**。回归测试改断上下界与单调性
  /// （正文越长 → 内容段越高、图片段越矮），不要再改回 `closeTo(0.65)` ——
  /// 那会让「按内容伸缩」这件事在测试里彻底失效。
  static const double brandShare = 0.15;

  /// 内容段（信息区）在**非品牌区**里的上下界（骨架默认值，业务卡可覆写）。
  ///
  /// 段高本身**取内容实际需要的高度**，这两个数只是把它夹住：
  /// - 下界 [contentMinOfRest]：正文只有一行时，段高不至于缩到只剩作者行紧贴图片，
  ///   下方还留得出一点呼吸；也保证 `1 - 上界` 之外总有确定的图片高度可算。
  /// - 上界 [contentMaxOfRest]：正文再长也不能把图片挤没。9:16 上 0.45 约 6 行，
  ///   超出部分由内容自己按剩余高度收行（`…` 截断），**不会溢出**。
  ///
  /// ⚠️ 写成「占非品牌区的比例」而不是「占整卡的比例」：品牌段在 1:1 上会被二维码
  /// 抬高（见 [brandBand]），若按整卡算，1:1 上的上界会连带吃掉本就不多的图片高度。
  static const double contentMinOfRest = 0.18;
  static const double contentMaxOfRest = 0.45;

  /// UI 稿 SH2 换算表（稿上卡宽 210px → 占卡宽的比例）。改这里请对着稿改。
  ///
  /// | 元素 | 稿上 | 占卡宽 | 修复前 |
  /// |---|---|---|---|
  /// | 内边距 | 14 | 0.0667 | 0.055 |
  /// | 头像 | 26 | 0.124 | 0.075 |
  /// | 作者名 | 11.5 | 0.055 | 0.036 |
  /// | 正文 | 13 | 0.062 | 0.042 |
  /// | 扫码提示 | 9 | 0.043 | 0.026 |
  /// | 品牌字标宽 | 47 | 0.224 | 0.20 |
  /// | 二维码 | 48 | 0.229 | 按**画布高**算，9:16 上 0.204 |
  /// | 分隔线 | 1px | —— | 原为 0.119 的**宽带**，固定分段后压成一条线（宽带塞不进 15%） |
  ///
  /// 头像 / 作者名 / 正文三项只有帖子卡用，留在 `ShareCardTemplate`；其余在此。
  static const double padFraction = 0.0667;
  static const double hintFontFraction = 0.043;
  static const double wordmarkFraction = 0.224;

  /// 二维码边长，**不得低于** [CardQr.minExportSide]。
  ///
  /// 🔴 原本按**画布高度**算（0.115），于是同一张码在 9:16 上 220px、1:1 上要靠
  /// 140 的下限兜底 —— 同一个设计元素在两种画布上大小不一致。改成跟排版单位走。
  ///
  /// ⚠️ 取 0.204 而不是设计稿的 0.229：稿值会把码放大到 247px，而实机反馈正是
  /// 「留给二维码的空间太大」。0.204 使 9:16 上的码维持在 220px（与修复前一致），
  /// 只把它周围**多余的留白**收掉。要严格对稿改这一个数即可。
  static const double qrFraction = 0.204;

  /// 下半部分的**排版单位**。所有字号 / 间距 / 头像 / 二维码都按它的比例算。
  ///
  /// 🔴 为什么不是直接用 `canvas.width`（bug 20260826）：
  /// UI 稿 SH2 的尺寸是**卡宽的比例**，但它只画了 9:16。原实现把这些比例
  /// 又整体缩了三分之一（见下表），于是卡面文字比设计稿小一大截 —— 实机上
  /// 「正文字太小、和设计不一样」正是这么来的。而若把设计比例原样套到 1:1 上，
  /// 下半部分会吃掉整张卡的 74%（1:1 画布只有 1080 高，9:16 有 1920）。
  /// 所以按画布高度相对 9:16 收缩，**9:16 上完全等于设计稿**，1:1 上等比缩小。
  ///
  /// ⚠️ 0.72 的下限不是凑数：再小二维码就会跌破 [CardQr.minExportSide]=140 的
  /// 可扫底线（1:1 上 220×0.72≈158，已经离得不远）。
  double get u => canvas.width * (canvas.height / CardCanvas.story.height).clamp(0.72, 1.0);

  double get pad => u * padFraction;

  double get dividerPx => u * 0.0015;
  double get brandVPad => u * 0.008;

  /// 品牌段最少要多高，**由二维码反算**（不是拍一个数）。
  ///
  /// 🔴 二维码的可扫底线是 [CardQr.minExportSide]=140px，而它**实际占位是 1.38 倍**——
  /// 四周必须留 4 个码元的静默区（[CardQr.footprintFor]），少了就扫不出来。
  /// 所以真正要预留的是 140×1.381 ≈ 193px，再加一条分隔线与上下留白。
  ///
  /// ⚠️ **写成反算而不是写死 19%**：这个数同时取决于画布高、可扫底线、静默区规则。
  /// 写死的话，将来任何一处一动，代码会安静地产出一张**扫不出来的码** ——
  /// 而二维码是卡片发到 Story 后唯一的转化通路，坏了没人会立刻发现。
  double get minBrandPx =>
      CardQr.footprintFor(CardQr.minExportSide) + dividerPx + brandVPad * 2;

  /// 品牌段实际占比：取「产品定的 15%」与「二维码装得下的最小值」中的**较大者**。
  ///
  /// - 9:16（1920 高）：15% = 288px，远够 ⇒ 严格 15%（产品红框量的就是这一档）。
  /// - 1:1（1080 高）：15% 只有 162px < 193px ⇒ **装不下可扫的码**，抬到约 19%。
  ///   差额从图片与内容按 65:20 的比例扣（产品 2026-08-28 拍板）。
  double get brandBand => math.max(brandShare, minBrandPx / canvas.height);

  /// 非品牌区（主体 + 信息）的总高度，px。主体段与信息段在这里面分。
  double get restPx => canvas.height * (1 - brandBand);
}

/// 分享卡**通用骨架**：主体区插槽 + 信息区 + 固定 15% 品牌段（v1.3.2 Story 4.1 · AD-14）。
///
/// 帖子卡（`ShareCardTemplate`）是它的一种用法；本批次的结果卡 / 护照卡 / 登机牌卡
/// 是另外几种。各卡**共用同一条品牌段**（字标 + 扫码提示 + 二维码），
/// 二维码内容由调用方传入（帖子卡印 `?src=qr` 的单条链接，本批次四类卡印 `/get`）。
///
/// 两种入口：
/// - 默认构造：「主体 `Expanded` 吃余量 + 信息 `ConstrainedBox` 被量」两段式（SH2）；
/// - [ShareCardSkeleton.block]：非品牌区整块交给调用方（SH3 纯文字模板的结构
///   与两段式不同，**不硬塞**进主体 + 信息）。
///
/// 🔴 **本组件按画布坐标系排版**（1 单位 = 导出图 1 像素），必须放进 `CardFrame` 里用。
class ShareCardSkeleton extends StatelessWidget {
  /// 两段式：主体（吃余量）+ 信息（按内容高度，夹在上下界之间）。
  const ShareCardSkeleton({
    super.key,
    required this.canvas,
    required Widget this.main,
    required Widget this.info,
    required this.qrData,
    this.color,
    this.gradient,
    this.mainAreaKey,
    this.infoAreaKey,
    this.infoMinOfRest = ShareCardMetrics.contentMinOfRest,
    this.infoMaxOfRest = ShareCardMetrics.contentMaxOfRest,
  }) : block = null;

  /// 整块式：非品牌区整块（高度 = 画布 × (1 − 品牌段)）交给 [block]。
  const ShareCardSkeleton.block({
    super.key,
    required this.canvas,
    required Widget this.block,
    required this.qrData,
    this.color,
    this.gradient,
  })  : main = null,
        info = null,
        mainAreaKey = null,
        infoAreaKey = null,
        infoMinOfRest = ShareCardMetrics.contentMinOfRest,
        infoMaxOfRest = ShareCardMetrics.contentMaxOfRest;

  /// 品牌段 key（回归测试量占比用）。帖子卡沿用同名常量。
  static const String brandAreaKey = 'shareCardBrandArea';

  final CardCanvas canvas;
  final Widget? main;
  final Widget? info;
  final Widget? block;

  /// 品牌段二维码内容。
  final String qrData;

  /// 底色 / 渐变（二选一；渐变正是二维码必须带白色底板的原因）。
  final Color? color;
  final Gradient? gradient;

  /// 主体区 / 信息区挂的 key（帖子卡用它量三段占比）。
  final Key? mainAreaKey;
  final Key? infoAreaKey;

  /// 信息段在非品牌区里的上下界。
  final double infoMinOfRest;
  final double infoMaxOfRest;

  ShareCardMetrics get metrics => ShareCardMetrics(canvas);

  @override
  Widget build(BuildContext context) {
    final m = metrics;
    return ClipRRect(
      borderRadius: BorderRadius.circular(canvas.radius),
      child: DecoratedBox(
        decoration: BoxDecoration(color: color, gradient: gradient),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              height: m.restPx,
              child: block ?? _twoSection(m),
            ),
            // 品牌 15%（字标 + 扫码引导 + 二维码）—— 这一段**不参与伸缩**。
            SizedBox(
              key: const ValueKey(brandAreaKey),
              height: canvas.height * m.brandBand,
              child: ShareCardBrandBand(metrics: m, qrData: qrData),
            ),
          ],
        ),
      ),
    );
  }

  /// ⚠️ 顺序不能反：`Column` 先量非弹性子节点、再把余量给 `Expanded`。
  /// 所以信息段必须是那个被量的（`ConstrainedBox`），主体段才是拿余量的（`Expanded`）。
  /// 反过来写会让主体抢先占满、信息段被压成 0。
  Widget _twoSection(ShareCardMetrics m) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 主体吃掉信息段用剩的全部高度。
        Expanded(child: SizedBox.expand(key: mainAreaKey, child: main)),
        // 信息段：高度 = 内容实际需要，夹在上下界之间。
        ConstrainedBox(
          key: infoAreaKey,
          constraints: BoxConstraints(
            minHeight: m.restPx * infoMinOfRest,
            maxHeight: m.restPx * infoMaxOfRest,
          ),
          child: info,
        ),
      ],
    );
  }
}

/// 品牌段：分隔线 + 字标 + 扫码引导 + 二维码。**整段高度由调用方定死**（画布的 15%）。
///
/// 🔴 二维码边长**从段高反推**，不再是一个独立比例。
/// 改前它按排版单位算（9:16 是 220、1:1 是 158），而分隔线还占了一条 0.119 的宽带 ——
/// 两者加起来 9:16 要 350px、1:1 要 251px，都**塞不进 15% 的段**（288 / 162）。
/// 固定分段之后，尺寸必须反过来服从段高，否则就是溢出。
///
/// ⚠️ 1:1 是**最紧的那一侧**：段高只有 162，减掉分隔线与上下留白剩 ~149，
/// 而二维码有 140 的可扫底线（[CardQr.minExportSide]）—— 余量只有 9px。
/// 想再压品牌段的比例前先算这一步，`CardQr` 的构造期 assert 会当场拦下，但那是运行时。
///
/// 二维码是**卡片导出到 Stories 后唯一的转化通路**（观看者点不了图上的链接）。
class ShareCardBrandBand extends StatelessWidget {
  const ShareCardBrandBand({
    super.key,
    required this.metrics,
    required this.qrData,
    this.withDivider = true,
  });

  final ShareCardMetrics metrics;
  final String qrData;
  final bool withDivider;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final m = metrics;
    final u = m.u;
    final vPad = m.brandVPad;
    return LayoutBuilder(builder: (context, c) {
      final lineH = withDivider ? m.dividerPx : 0.0;
      final avail = c.maxHeight - lineH - vPad * 2;
      // 🔴 按**占位**反推边长，不是拿 avail 当边长：`CardQr` 四周还有静默区，
      //    实际占位是边长的 1.381 倍（[CardQr.footprintFor]）。
      //    第一版直接把 avail 当边长传进去 —— 1:1 上码被压到 87px、扫不出来，
      //    是既有的「导出图里码 ≥140」那条用例当场抓住的。
      final fitByHeight = avail / (CardQr.footprintFor(1));
      final qrSide = math.max(CardQr.minExportSide,
          math.min(fitByHeight, u * ShareCardMetrics.qrFraction));
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (withDivider)
            Container(height: lineH, width: double.infinity, color: AppColors.line2),
          Expanded(
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: m.pad, vertical: vPad),
              child: Row(
                // 🔴 垂直居中（UI 稿 SH2 的 align-items:center）：底对齐会让左边的字标+提示
                // 沉到行底，与二维码错开半个身位 —— 那正是 2026-08-26 实机反馈的样子。
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    child: Column(
                      // 🛡 `min` 不能省：默认 `max` 会把左列撑到与二维码等高，居中也就失去意义。
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SvgPicture.asset(
                          'assets/brand/wordmark_brand.svg',
                          width: u * ShareCardMetrics.wordmarkFraction,
                          // 🔴 这个字标资产是**纯白**的（给紫底启动页做的，见 splash_page）。
                          //    卡面是白底 ⇒ 不上色就是**白字画在白纸上，整个 logo 隐形** ——
                          //    实机反馈「设计稿里的 logo 为什么不见了」就是这个原因。
                          colorFilter:
                              const ColorFilter.mode(AppColors.mint, BlendMode.srcIn),
                        ),
                        SizedBox(height: u * 0.029),
                        Text(
                          l10n.shareCardScanHint,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: u * ShareCardMetrics.hintFontFraction,
                            color: AppColors.muted,
                          ),
                        ),
                      ],
                    ),
                  ),
                  CardQr(data: qrData, side: qrSide),
                ],
              ),
            ),
          ),
        ],
      );
    });
  }
}
