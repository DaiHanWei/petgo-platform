import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_qr.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/letter_avatar.dart';
import '../../domain/content_type_badge.dart';
import '../../domain/share_card_data.dart';

/// 分享卡卡面（Story 9.2 · UI 稿 SH2 / SH3）。
///
/// 两套模板由 [ShareCardData.hasImage] **单点决定**：
/// - 有图 → 图文模板（SH2）：首图 + 作者 + 摘要 + 品牌 + 二维码
/// - 无图 → 纯文字模板（SH3）：不放图片区、文字占满主体，其余照旧
///
/// 🛡 纯文字模板**不是降级形态**，它是覆盖率的必要件：Moment / Knowledge
/// 大量是纯文字帖，出不了卡等于这功能对一半内容不存在（AD-15 Rule 3）。
///
/// 🔴 **本组件按画布坐标系排版**（1 单位 = 导出图 1 像素），必须放进
/// `CardFrame` 里用。所有尺寸都是从 [canvas] 按比例算的 ——
/// 换画布（9:16 / 1:1）不需要改这里任何一个数字。
class ShareCardTemplate extends StatelessWidget {
  const ShareCardTemplate({super.key, required this.data, required this.canvas});

  /// 三段的 key（回归测试量占比用）。
  static const String shareCardImageAreaKey = 'shareCardImageArea';
  static const String shareCardContentAreaKey = 'shareCardContentArea';
  static const String shareCardBrandAreaKey = 'shareCardBrandArea';

  final ShareCardData data;
  final CardCanvas canvas;

  /// 🔴 **卡面三段占比**（产品 2026-08-28 定稿）：图片 65% / 作者+正文 20% / 品牌 15%。
  ///
  /// <h2>这一版的取舍（产品 2026-08-28 二次拍板）</h2>
  /// 分两半看：
  /// - **品牌段固定** 15%（1:1 上被二维码底线抬高，见 [_brandBand]）——
  ///   一批卡混着发到 Story 里**成套**靠的就是这条底边一样高。
  /// - **上面 85% 按内容伸缩**，只夹上下界（[_contentMinOfRest] / [_contentMaxOfRest]）。
  ///
  /// 🔴 这是对同日早些时候「三段全固定」的**修正**，不是把它推翻重来：
  /// 全固定确实让每张卡一样高，但代价是一行短文案时内容段白掉约 100px ——
  /// 而那 100px 本该给图片。产品看过实机后判定「白一条」比「图文比例略有差异」更刺眼，
  /// 于是只保留真正决定成套感的那一段（品牌段）固定。
  ///
  /// ⚠️ 因此**图片段不再是一个可以写死断言的数**。回归测试改断上下界与单调性
  /// （正文越长 → 内容段越高、图片段越矮），不要再改回 `closeTo(0.65)` ——
  /// 那会让「按内容伸缩」这件事在测试里彻底失效。
  static const double _brandShare = 0.15;

  /// 内容段（作者行 + 正文）在**非品牌区**里的上下界。
  ///
  /// 段高本身**取正文实际需要的高度**，这两个数只是把它夹住：
  /// - 下界 [_contentMinOfRest]：正文只有一行时，段高不至于缩到只剩作者行紧贴图片，
  ///   下方还留得出一点呼吸；也保证 `1 - 上界` 之外总有确定的图片高度可算。
  /// - 上界 [_contentMaxOfRest]：正文再长也不能把图片挤没。9:16 上 0.45 约 6 行，
  ///   超出部分由 [_body] 按剩余高度自己收行（`…` 截断），**不会溢出**。
  ///
  /// ⚠️ 写成「占非品牌区的比例」而不是「占整卡的比例」：品牌段在 1:1 上会被二维码
  /// 抬高（见 [_brandBand]），若按整卡算，1:1 上的上界会连带吃掉本就不多的图片高度。
  static const double _contentMinOfRest = 0.18;
  static const double _contentMaxOfRest = 0.45;

  /// 品牌段最少要多高，**由二维码反算**（不是拍一个数）。
  ///
  /// 🔴 二维码的可扫底线是 [CardQr.minExportSide]=140px，而它**实际占位是 1.38 倍**——
  /// 四周必须留 4 个码元的静默区（[CardQr.footprintFor]），少了就扫不出来。
  /// 所以真正要预留的是 140×1.381 ≈ 193px，再加一条分隔线与上下留白。
  ///
  /// ⚠️ **写成反算而不是写死 19%**：这个数同时取决于画布高、可扫底线、静默区规则。
  /// 写死的话，将来任何一处一动，代码会安静地产出一张**扫不出来的码** ——
  /// 而二维码是卡片发到 Story 后唯一的转化通路，坏了没人会立刻发现。
  double get _minBrandPx =>
      CardQr.footprintFor(CardQr.minExportSide) + _dividerPx + _brandVPad * 2;

  /// 品牌段实际占比：取「产品定的 15%」与「二维码装得下的最小值」中的**较大者**。
  ///
  /// - 9:16（1920 高）：15% = 288px，远够 ⇒ 严格 15%（产品红框量的就是这一档）。
  /// - 1:1（1080 高）：15% 只有 162px < 193px ⇒ **装不下可扫的码**，抬到约 19%。
  ///   差额从图片与内容按 65:20 的比例扣（产品 2026-08-28 拍板）。
  double get _brandBand => math.max(_brandShare, _minBrandPx / canvas.height);

  /// 非品牌区（图片 + 内容）的总高度，px。图片段与内容段在这里面分。
  double get _restPx => canvas.height * (1 - _brandBand);

  double get _dividerPx => _u * 0.0015;
  double get _brandVPad => _u * 0.008;

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
  double get _u => canvas.width * (canvas.height / CardCanvas.story.height).clamp(0.72, 1.0);

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
  static const double _padFraction = 0.0667;
  static const double _avatarFraction = 0.124;
  static const double _authorFontFraction = 0.055;
  static const double _bodyFontFraction = 0.062;
  static const double _hintFontFraction = 0.043;
  static const double _wordmarkFraction = 0.224;

  /// 二维码边长，**不得低于** [CardQr.minExportSide]。
  ///
  /// 🔴 原本按**画布高度**算（0.115），于是同一张码在 9:16 上 220px、1:1 上要靠
  /// 140 的下限兜底 —— 同一个设计元素在两种画布上大小不一致。改成跟排版单位走。
  ///
  /// ⚠️ 取 0.204 而不是设计稿的 0.229：稿值会把码放大到 247px，而实机反馈正是
  /// 「留给二维码的空间太大」。0.204 使 9:16 上的码维持在 220px（与修复前一致），
  /// 只把它周围**多余的留白**收掉。要严格对稿改这一个数即可。
  static const double _qrFraction = 0.204;


  double get _pad => _u * _padFraction;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final body = data.hasImage ? _imageLayout(l10n) : _textLayout(l10n);

    return ClipRRect(
      borderRadius: BorderRadius.circular(canvas.radius),
      child: DecoratedBox(
        decoration: BoxDecoration(
          // 图文模板白底；纯文字模板紫色渐变（UI 稿 SH3）——
          // 🔴 渐变正是二维码必须带白色底板的原因（AD-15 Rule 4 第 4 条）。
          color: data.hasImage ? Colors.white : null,
          gradient: data.hasImage
              ? null
              : const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [AppColors.mintTint, Colors.white],
                ),
        ),
        child: body,
      ),
    );
  }

  // ——— SH2 图文模板 ———
  ///
  /// 🔴 **品牌段固定、上面按内容伸缩**（产品 2026-08-28 二次拍板，取舍见类头注释）：
  /// 品牌段 15%（1:1 上被二维码底线抬高）；余下的高度里，内容段取正文实际需要的高度、
  /// 夹在 [_contentMinOfRest]~[_contentMaxOfRest] 之间，**剩下的全部归图片**。
  ///
  /// ⚠️ 顺序不能反：`Column` 先量非弹性子节点、再把余量给 `Expanded`。
  /// 所以内容段必须是那个被量的（`ConstrainedBox`），图片段才是拿余量的（`Expanded`）。
  /// 反过来写会让图片抢先占满、内容段被压成 0。
  Widget _imageLayout(AppLocalizations l10n) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 图片区 + 内容区：合起来是非品牌区，内部按内容分。
        SizedBox(
          height: _restPx,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 图片吃掉内容段用剩的全部高度。
              // key 供回归测试量占比 —— 没有它就只能靠人眼看截图。
              Expanded(
                child: SizedBox.expand(
                  key: const ValueKey(shareCardImageAreaKey),
                  child: AppImage.widget(
                    data.imageUrl!,
                    fit: BoxFit.cover,
                    // 🔴 缩略图宽度按**画布宽度**取，不是按预览宽度。
                    // 按预览宽度（屏幕上可能只有 300px）取图，导出的 1080 大图里首图是糊的
                    // —— 与二维码那条是同一个坑的两种表现。
                    thumbWidth: canvas.width.round(),
                    errorBuilder: (_, _, _) =>
                        const ColoredBox(color: AppColors.mintTint),
                  ),
                ),
              ),
              // 作者 + 正文：高度 = 内容实际需要，夹在上下界之间。
              ConstrainedBox(
                key: const ValueKey(shareCardContentAreaKey),
                constraints: BoxConstraints(
                  minHeight: _restPx * _contentMinOfRest,
                  maxHeight: _restPx * _contentMaxOfRest,
                ),
                child: Padding(
                  padding: EdgeInsets.fromLTRB(_pad, _pad * 0.55, _pad, _pad * 0.4),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _authorRow(l10n),
                      if ((data.body ?? '').isNotEmpty) ...[
                        SizedBox(height: _u * 0.043),
                        // ⚠️ `Flexible` 而不是 `Expanded`：正文短就只占它需要的高度
                        //    （整段能跟着缩，靠的就是这一点）；正文长到顶到上界时，
                        //    由 [_body] 按剩余高度自己收行 —— 不会溢出。
                        Flexible(
                          child: _body(
                              fontSize: _u * _bodyFontFraction,
                              weight: FontWeight.w400),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        // 品牌 15%（字标 + 扫码引导 + 二维码）—— 这一段**不参与伸缩**。
        SizedBox(
          key: const ValueKey(shareCardBrandAreaKey),
          height: canvas.height * _brandBand,
          child: _footer(l10n, withDivider: true),
        ),
      ],
    );
  }

  // ——— SH3 纯文字模板（不放图片区）———
  ///
  /// 🔴 品牌段同样占 15%（产品 2026-08-28「统一一下」）—— 两套模板的品牌区一样高，
  /// 一批卡混着发到 Story 里才成套。余下 85% 全归文字（无图，图片段那 65% 并入正文）。
  Widget _textLayout(AppLocalizations l10n) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: canvas.height * (1 - _brandBand),
          child: Padding(
            padding: EdgeInsets.all(_pad * 1.2),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _typeChip(l10n),
                SizedBox(height: _pad),
                // 文字占满主体：字号比图文模板大一档、半粗，正文自己就是主视觉。
                Expanded(
                    child: _body(fontSize: _u * 0.055, weight: FontWeight.w600)),
                SizedBox(height: _pad),
                _authorRow(l10n),
              ],
            ),
          ),
        ),
        SizedBox(
          key: const ValueKey(shareCardBrandAreaKey),
          height: canvas.height * _brandBand,
          child: _footer(l10n, withDivider: true),
        ),
      ],
    );
  }

  Widget _typeChip(AppLocalizations l10n) {
    final badge = ContentTypeBadge.of(data.type, l10n);
    return Container(
      padding: EdgeInsets.symmetric(horizontal: _pad * 0.5, vertical: _pad * 0.24),
      decoration: BoxDecoration(
        color: badge.bg,
        borderRadius: BorderRadius.circular(canvas.width * 0.02),
      ),
      child: Text(
        badge.label,
        style: TextStyle(
          fontSize: canvas.width * 0.032,
          fontWeight: FontWeight.w700,
          color: badge.fg,
        ),
      ),
    );
  }

  Widget _authorRow(AppLocalizations l10n) {
    final avatarSize = _u * _avatarFraction;
    return Row(
      children: [
        LetterAvatar(
          url: data.authorAvatarUrl,
          name: data.authorName,
          deleted: data.authorDeleted,
          size: avatarSize,
        ),
        SizedBox(width: _pad * 0.45),
        Expanded(
          child: Text(
            data.authorName,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: _u * _authorFontFraction,
              fontWeight: FontWeight.w700,
              color: AppColors.ink,
            ),
          ),
        ),
      ],
    );
  }

  /// 正文。
  ///
  /// ⚠️ `Text` 只按 `maxLines` 截断，**不会按剩余高度自己收住** ——
  /// 直接塞进 `Expanded` 里，长正文会画出溢出条纹。所以按可用高度算出行数。
  Widget _body({required double fontSize, required FontWeight weight}) {
    const lineHeight = 1.5;
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxLines = (constraints.maxHeight / (fontSize * lineHeight)).floor();
        if (maxLines < 1) return const SizedBox.shrink();
        return Align(
          alignment: Alignment.topLeft,
          // ⚠️ `heightFactor: 1` 不能省：`Align` 默认**撑满**可用高度。
          //    图文模板里正文放在 loose `Flexible` 下，撑满就等于那条白又回来了
          //    （改布局时正是在这里被绊了一下）。纯文字模板那边它在 `Expanded`
          //    里、约束是紧的，撑满与否都一样 —— 所以这一行对两处都安全。
          heightFactor: 1,
          child: Text(
            data.body ?? '',
            maxLines: maxLines,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: fontSize,
              height: lineHeight,
              fontWeight: weight,
              color: AppColors.ink,
            ),
          ),
        );
      },
    );
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
  Widget _footer(AppLocalizations l10n, {required bool withDivider}) {
    final vPad = _brandVPad;
    return LayoutBuilder(builder: (context, c) {
      final lineH = withDivider ? _dividerPx : 0.0;
      final avail = c.maxHeight - lineH - vPad * 2;
      // 🔴 按**占位**反推边长，不是拿 avail 当边长：`CardQr` 四周还有静默区，
      //    实际占位是边长的 1.381 倍（[CardQr.footprintFor]）。
      //    第一版直接把 avail 当边长传进去 —— 1:1 上码被压到 87px、扫不出来，
      //    是既有的「导出图里码 ≥140」那条用例当场抓住的。
      final fitByHeight = avail / (CardQr.footprintFor(1) );
      final qrSide = math.max(
          CardQr.minExportSide, math.min(fitByHeight, _u * _qrFraction));
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (withDivider)
            Container(height: lineH, width: double.infinity, color: AppColors.line2),
          Expanded(
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: _pad, vertical: vPad),
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
                          width: _u * _wordmarkFraction,
                          // 🔴 这个字标资产是**纯白**的（给紫底启动页做的，见 splash_page）。
                          //    卡面是白底 ⇒ 不上色就是**白字画在白纸上，整个 logo 隐形** ——
                          //    实机反馈「设计稿里的 logo 为什么不见了」就是这个原因。
                          colorFilter:
                              const ColorFilter.mode(AppColors.mint, BlendMode.srcIn),
                        ),
                        SizedBox(height: _u * 0.029),
                        Text(
                          l10n.shareCardScanHint,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: _u * _hintFontFraction,
                            color: AppColors.muted,
                          ),
                        ),
                      ],
                    ),
                  ),
                  // 🔴 码里印的是带 `?src=qr` 的变体 —— 见 [ShareCardData.qrUrl]。
                  // 印 shareUrl 会让 E-14 的 open_method 永远分不出 qr。
                  CardQr(data: data.qrUrl, side: qrSide),
                ],
              ),
            ),
          ),
        ],
      );
    });
  }

}
