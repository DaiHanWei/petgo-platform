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

  /// 图片区的 key（回归测试量图片占比用）。
  static const String shareCardImageAreaKey = 'shareCardImageArea';

  final ShareCardData data;
  final CardCanvas canvas;

  /// 图片区占画布高度的**下界**（长正文时）。
  ///
  /// 这个数原本是**唯一**的图片高度比例，两种画布共用。1:1 下看着正常，
  /// 9:16 下就出事了：画布高 1920，42% 给图、剩下 1114px 全归文字区 ——
  /// 而「disini」这种一行的短文案根本填不满，卡面中间空出一大条白
  /// （2026-08-26 实机截图）。
  static const double _minImageFraction = 0.42;

  /// 图片区占画布高度的**上界**（短正文时）。
  ///
  /// 🔴 有上界才不会走到另一个极端：图片顶到底、作者名与二维码被挤成一条缝。
  /// 0.64 是让 9:16 的图片区略高于正方形（1080×1229）—— 竖屏 Story 里
  /// 这个形状最接近观看者手机的取景，同时下半部分仍留得住
  /// 「作者 + 三四行文案 + 分隔线 + 二维码」。
  static const double _maxImageFraction = 0.64;

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
  /// | 分隔线上下 | 14+11 | 0.119 | 0.055 |
  static const double _padFraction = 0.0667;
  static const double _avatarFraction = 0.124;
  static const double _authorFontFraction = 0.055;
  static const double _bodyFontFraction = 0.062;
  static const double _hintFontFraction = 0.043;
  static const double _wordmarkFraction = 0.224;
  static const double _dividerBandFraction = 0.119;

  /// 二维码边长，**不得低于** [CardQr.minExportSide]。
  ///
  /// 🔴 原本按**画布高度**算（0.115），于是同一张码在 9:16 上 220px、1:1 上要靠
  /// 140 的下限兜底 —— 同一个设计元素在两种画布上大小不一致。改成跟排版单位走。
  ///
  /// ⚠️ 取 0.204 而不是设计稿的 0.229：稿值会把码放大到 247px，而实机反馈正是
  /// 「留给二维码的空间太大」。0.204 使 9:16 上的码维持在 220px（与修复前一致），
  /// 只把它周围**多余的留白**收掉。要严格对稿改这一个数即可。
  static const double _qrFraction = 0.204;

  double get _qrSide => math.max(CardQr.minExportSide, _u * _qrFraction);

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
  /// 🔴 **下半部分按内容收缩，剩下的全给图片** —— 不是给图片一个固定比例。
  ///
  /// 原来的写法是「图片 = 画布高 × 0.42，其余归文字」，两种画布共用那一个数。
  /// 后果是 9:16 下短文案会在卡面中间留一大条白（见 [_minImageFraction]）。
  /// 把主次颠倒过来之后：文案一行就只占一行的高度，图片自然长满余下的空间；
  /// 文案很长时下半部分撑大、图片退回 [_minImageFraction] 的下界，
  /// **长文案不会因为这次修复而被多截掉一行**。
  ///
  /// 上下界钉在 [_maxImageFraction] / [_minImageFraction] 两端，
  /// 所以两种画布都不需要各写一份数字（沿用本类「尺寸都从 canvas 算」的约定）。
  Widget _imageLayout(AppLocalizations l10n) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 图片区：拿走下半部分之外的全部高度。
        // key 供回归测试量「图片占了画布多少」—— 这条修复的全部内容就是这个比例，
        // 没有它就只能靠人眼看截图。
        Expanded(
          key: const ValueKey(shareCardImageAreaKey),
          child: AppImage.widget(
            data.imageUrl!,
            fit: BoxFit.cover,
            // 🔴 缩略图宽度按**画布宽度**取，不是按预览宽度。
            // 按预览宽度（屏幕上可能只有 300px）取图，导出的 1080 大图里首图是糊的
            // —— 与二维码那条是同一个坑的两种表现。
            thumbWidth: canvas.width.round(),
            errorBuilder: (_, _, _) => const ColoredBox(color: AppColors.mintTint),
          ),
        ),
        // 下半部分：高度由内容决定，只在上下界之间浮动。
        // ⚠️ `Column` 先按无界高度量非弹性子级、再把余量分给 `Expanded`，
        //    所以这里必须 `mainAxisSize.min` + 外层 `ConstrainedBox` 夹住 ——
        //    少了 min 它会撑满，图片又回到「被固定比例分掉」的老样子。
        ConstrainedBox(
          constraints: BoxConstraints(
            minHeight: canvas.height * (1 - _maxImageFraction),
            maxHeight: canvas.height * (1 - _minImageFraction),
          ),
          child: Padding(
            padding: EdgeInsets.all(_pad),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _authorRow(l10n),
                if ((data.body ?? '').isNotEmpty) ...[
                  SizedBox(height: _u * 0.043),
                  // ⚠️ `Flexible`（loose）而不是 `Expanded`：正文短就只占它需要的高度，
                  //    长了才吃掉余量并由 [_body] 按可用高度收行。
                  //    用 `Expanded` 会强行占满 ⇒ 空白照旧。
                  Flexible(
                    child: _body(fontSize: _u * _bodyFontFraction, weight: FontWeight.w400),
                  ),
                ],
                _footer(l10n, withDivider: true),
              ],
            ),
          ),
        ),
      ],
    );
  }

  // ——— SH3 纯文字模板（不放图片区）———
  Widget _textLayout(AppLocalizations l10n) {
    return Padding(
      padding: EdgeInsets.all(_pad * 1.2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _typeChip(l10n),
          SizedBox(height: _pad),
          // 文字占满主体：字号比图文模板大一档、半粗，正文自己就是主视觉。
          Expanded(child: _body(fontSize: canvas.width * 0.055, weight: FontWeight.w600)),
          SizedBox(height: _pad),
          _authorRow(l10n),
          SizedBox(height: _pad * 0.6),
          _footer(l10n, withDivider: true),
        ],
      ),
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

  /// 页脚：品牌字标 + 扫码引导 + 二维码。
  ///
  /// 二维码是**卡片导出到 Stories 后唯一的转化通路**（观看者点不了图上的链接）。
  ///
  /// 🔴 **整块只占一行、紧贴二维码**（bug 20260826）。修复前是
  /// `crossAxisAlignment.end` + 左列 `mainAxisAlignment.end`：行高被二维码撑到
  /// 220px，而左边字标+提示只有百来 px 且被压到行底 —— 实机上看就是
  /// 「二维码那一格空出一大块、提示文字孤零零掉在左下角」。
  /// 现按 UI 稿 SH2 的 `align-items:center` 垂直居中，行高就等于二维码本身。
  Widget _footer(AppLocalizations l10n, {required bool withDivider}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (withDivider) ...[
          // 稿上是 margin-top:14 + padding-top:11（合 0.119 卡宽）；`Divider.height`
          // 正是「含线在内的整条带高」，所以这一个数就够，不要再另加 SizedBox。
          Divider(
            height: _u * _dividerBandFraction,
            thickness: _u * 0.0015,
            color: AppColors.line2,
          ),
        ],
        // ⚠️ 上下各 1px：实机反馈明确要求这一行「比二维码上下宽 1px 就够」。
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 1),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Expanded(
                child: Column(
                  // 🛡 `min` 不能省：默认 `max` 会把左列撑到与二维码等高，
                  //    居中也就失去意义（字标与提示又会散开）。
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SvgPicture.asset(
                      'assets/brand/wordmark_brand.svg',
                      width: _u * _wordmarkFraction,
                      // 🔴 这个字标资产是**纯白**的（给紫底启动页做的，见 splash_page）。
                      //    卡面是白底 ⇒ 不上色就是**白字画在白纸上，整个 logo 隐形** ——
                      //    实机反馈「设计稿里的 logo 为什么不见了」就是这个原因，
                      //    而不是资产缺失或没进 pubspec（两者都正常）。
                      colorFilter: const ColorFilter.mode(AppColors.mint, BlendMode.srcIn),
                    ),
                    SizedBox(height: _u * 0.029),
                    Text(
                      l10n.shareCardScanHint,
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
              CardQr(data: data.qrUrl, side: _qrSide),
            ],
          ),
        ),
      ],
    );
  }
}
