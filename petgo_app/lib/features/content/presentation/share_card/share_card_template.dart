import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/letter_avatar.dart';
import '../../domain/content_type_badge.dart';
import '../../domain/share_card_data.dart';
import 'share_card_skeleton.dart';

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
///
/// v1.3.2 Story 4.1：三段比例、品牌段、排版单位已抽到通用骨架
/// [ShareCardSkeleton] / [ShareCardMetrics]（产品决定的注释随数一起搬过去了），
/// 本组件是骨架的一种用法 —— **导出像素与改造前逐字节一致**。

class ShareCardTemplate extends StatelessWidget {
  const ShareCardTemplate({super.key, required this.data, required this.canvas});

  /// 三段的 key（回归测试量占比用）。
  static const String shareCardImageAreaKey = 'shareCardImageArea';
  static const String shareCardContentAreaKey = 'shareCardContentArea';
  static const String shareCardBrandAreaKey = ShareCardSkeleton.brandAreaKey;

  final ShareCardData data;
  final CardCanvas canvas;

  /// 🔴 **卡面三段占比**（图片 / 作者+正文 / 品牌 15%）与「品牌段固定、上面按内容伸缩」
  /// 的取舍、二维码反算品牌段下限、排版单位 —— 全部见 [ShareCardMetrics]。
  ShareCardMetrics get _m => ShareCardMetrics(canvas);

  double get _u => _m.u;
  double get _pad => _m.pad;

  /// UI 稿 SH2 换算表里帖子卡独有的三项（整表见 [ShareCardMetrics.padFraction]）。
  ///
  /// | 元素 | 稿上 | 占卡宽 | 修复前 |
  /// |---|---|---|---|
  /// | 头像 | 26 | 0.124 | 0.075 |
  /// | 作者名 | 11.5 | 0.055 | 0.036 |
  /// | 正文 | 13 | 0.062 | 0.042 |
  static const double _avatarFraction = 0.124;
  static const double _authorFontFraction = 0.055;
  static const double _bodyFontFraction = 0.062;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 图文模板白底；纯文字模板紫色渐变（UI 稿 SH3）——
    // 🔴 渐变正是二维码必须带白色底板的原因（AD-15 Rule 4 第 4 条）。
    // 🔴 码里印的是带 `?src=qr` 的变体 —— 见 [ShareCardData.qrUrl]。
    // 印 shareUrl 会让 E-14 的 open_method 永远分不出 qr。
    return data.hasImage ? _imageLayout(l10n) : _textLayout(l10n);
  }

  // ——— SH2 图文模板 ———
  ///
  /// 🔴 **品牌段固定、上面按内容伸缩**（产品 2026-08-28 二次拍板，取舍见 [ShareCardMetrics]）：
  /// 品牌段 15%（1:1 上被二维码底线抬高）；余下的高度里，内容段取正文实际需要的高度、
  /// 夹在 [ShareCardMetrics.contentMinOfRest]~[ShareCardMetrics.contentMaxOfRest] 之间，
  /// **剩下的全部归图片**。图片就是骨架的主体区（`Expanded`），作者 + 正文是信息区（被量的那段）。
  Widget _imageLayout(AppLocalizations l10n) {
    return ShareCardSkeleton(
      canvas: canvas,
      color: Colors.white,
      qrData: data.qrUrl,
      // key 供回归测试量占比 —— 没有它就只能靠人眼看截图。
      mainAreaKey: const ValueKey(shareCardImageAreaKey),
      infoAreaKey: const ValueKey(shareCardContentAreaKey),
      main: AppImage.widget(
        data.imageUrl!,
        fit: BoxFit.cover,
        // 🔴 缩略图宽度按**画布宽度**取，不是按预览宽度。
        // 按预览宽度（屏幕上可能只有 300px）取图，导出的 1080 大图里首图是糊的
        // —— 与二维码那条是同一个坑的两种表现。
        thumbWidth: canvas.width.round(),
        errorBuilder: (_, _, _) => const ColoredBox(color: AppColors.mintTint),
      ),
      info: Padding(
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
                child: _body(fontSize: _u * _bodyFontFraction, weight: FontWeight.w400),
              ),
            ],
          ],
        ),
      ),
    );
  }

  // ——— SH3 纯文字模板（不放图片区）———
  ///
  /// 🔴 品牌段同样占 15%（产品 2026-08-28「统一一下」）—— 两套模板的品牌区一样高，
  /// 一批卡混着发到 Story 里才成套。余下 85% 全归文字（无图，图片段那 65% 并入正文）。
  /// ⚠️ 结构与两段式不同，走骨架的整块入口，不硬塞进「主体 + 信息」。
  Widget _textLayout(AppLocalizations l10n) {
    return ShareCardSkeleton.block(
      canvas: canvas,
      gradient: const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [AppColors.mintTint, Colors.white],
      ),
      qrData: data.qrUrl,
      block: Padding(
        padding: EdgeInsets.all(_pad * 1.2),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _typeChip(l10n),
            SizedBox(height: _pad),
            // 文字占满主体：字号比图文模板大一档、半粗，正文自己就是主视觉。
            Expanded(child: _body(fontSize: _u * 0.055, weight: FontWeight.w600)),
            SizedBox(height: _pad),
            _authorRow(l10n),
          ],
        ),
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
}
