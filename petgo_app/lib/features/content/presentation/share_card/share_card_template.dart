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

  final ShareCardData data;
  final CardCanvas canvas;

  /// 图片区占画布高度的比例。9:16 下约等于一个正方形，1:1 下是上半部分。
  static const double _imageHeightFraction = 0.42;

  /// 二维码边长 = 画布高度 × 此比例，但**不得低于** [CardQr.minExportSide]。
  ///
  /// 🔴 1:1 画布（1080 高）按比例只有 124px —— **低于 140 就扫不出来**。
  /// 所以码不能跟着画布一起缩：小画布上它占的版面比例反而更大，这是有意的。
  /// （第一版漏了这个 clamp，被 `CardQr` 的构造期 assert 当场拦下。）
  static const double _qrHeightFraction = 0.115;

  double get _qrSide => math.max(CardQr.minExportSide, canvas.height * _qrHeightFraction);

  double get _pad => canvas.width * 0.055;

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
  Widget _imageLayout(AppLocalizations l10n) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: canvas.height * _imageHeightFraction,
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
        Expanded(
          child: Padding(
            padding: EdgeInsets.all(_pad),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _authorRow(l10n),
                if ((data.body ?? '').isNotEmpty) ...[
                  SizedBox(height: _pad * 0.6),
                  Expanded(child: _body(fontSize: canvas.width * 0.042, weight: FontWeight.w400)),
                ] else
                  const Spacer(),
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
    final avatarSize = canvas.width * 0.075;
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
              fontSize: canvas.width * 0.036,
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
  Widget _footer(AppLocalizations l10n, {required bool withDivider}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (withDivider) ...[
          Divider(height: _pad, thickness: canvas.width * 0.0015, color: AppColors.line2),
        ],
        Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  SvgPicture.asset(
                    'assets/brand/wordmark_brand.svg',
                    width: canvas.width * 0.20,
                  ),
                  SizedBox(height: _pad * 0.3),
                  Text(
                    l10n.shareCardScanHint,
                    style: TextStyle(
                      fontSize: canvas.width * 0.026,
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
      ],
    );
  }
}
