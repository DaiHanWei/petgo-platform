import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/config/app_download_url.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_qr.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/letter_avatar.dart';
import '../../domain/human_age.dart';

/// 宠物年龄卡的卡面（V1.3.0 批次 A · Story 5.2 · AC5）。
///
/// 🔴 **按画布坐标系排版**（1 单位 = 导出图 1 像素），必须放进 `CardFrame` 里用。
/// 所有尺寸都从 [canvas] 按比例算 —— 换画布（9:16 / 1:1）不改这里任何一个数字。
///
/// 字段顺序（`文案需求清单.md` §5.0 的字段清单）：
/// 头像 + 名字 → 实际年龄 → **人类年龄当量（主视觉）** → 趣味文案 → 体型档（仅狗）→ 品牌带。
///
/// ## 🔴 不加水印
/// 水印只属 KTP / 护照那类**付费保护**场景（高清无水印图是要花钱的，预览带水印才防得住
/// 截屏白嫖）。年龄卡免费、且**越多人转发越好** —— 加水印既没有要保护的收入，
/// 又让卡面脏得看不清。
///
/// ⚠️ 卡面**具体视觉以设计稿为准**（PRD 2026-09-10 拍板）。设计稿未到，这里是纯色背景版，
/// 字段清单与顺序已按定稿落齐；换皮时只动颜色与背景，不要动字段顺序与二维码那三条硬要求。
class AgeCardTemplate extends StatelessWidget {
  const AgeCardTemplate({
    super.key,
    required this.canvas,
    required this.petName,
    required this.age,
    required this.quip,
    this.avatarUrl,
    this.sizeLabel,
  });

  final CardCanvas canvas;
  final String petName;
  final HumanAgeResult age;

  /// 已经取好的那一句趣味文案（随机在调用方做，**不在这里做** ——
  /// build 会被调用多次，放这里每帧都换一句）。
  final String quip;

  final String? avatarUrl;

  /// 体型档标签（**仅狗卡**有，如「Medium · 9–23 kg」）。猫卡传 null。
  final String? sizeLabel;

  /// 排版单位，口径与分享卡模板一致：9:16 上等于设计比例，1:1 上等比缩小。
  double get _u =>
      canvas.width * (canvas.height / CardCanvas.story.height).clamp(0.72, 1.0);

  double get _pad => _u * 0.0667;

  /// 品牌段高度：**由二维码反算**，不写死比例。
  ///
  /// 🔴 二维码可扫底线是 140px，而它实际占位是 1.38 倍（四周 4 个码元的静默区）。
  /// 写死比例的话，将来任一处一动，代码会安静地产出一张**扫不出来的码** ——
  /// 而二维码是卡片发到 Story 后唯一的转化通路，坏了没人会立刻发现。
  double get _brandPx => math.max(
        canvas.height * 0.15,
        CardQr.footprintFor(CardQr.minExportSide) + _pad * 2,
      );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ColoredBox(
      color: AppColors.card,
      child: Column(
        children: [
          Expanded(child: _body(l10n)),
          SizedBox(height: _brandPx, child: _brandBand(l10n)),
        ],
      ),
    );
  }

  Widget _body(AppLocalizations l10n) => Padding(
        padding: EdgeInsets.all(_pad * 1.6),
        // 🔴 scaleDown 兜底：字段是固定的那几样，但**内容长度不是** ——
        // 印尼语文案更长、狗卡多一条体型带、1:1 画布本来就矮一截。
        // 这三件事叠在一起会把 Column 顶溢出（1:1 上必溢），而卡面溢出就是一张废图。
        // 按比例整体缩，比逐个字号写死一套 1:1 的数更不容易再坏。
        child: FittedBox(
          fit: BoxFit.scaleDown,
          // 文字要能折行就得有个有界宽度 —— FittedBox 给的是无界约束。
          child: SizedBox(
          width: canvas.width - _pad * 3.2,
          child: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // 头像：没有就用字母头像（与站内其它地方同一件共享件，不另画）。
            SizedBox(
              width: _u * 0.30,
              height: _u * 0.30,
              child: avatarUrl == null || avatarUrl!.isEmpty
                  ? LetterAvatar(name: petName, size: _u * 0.30)
                  : ClipOval(
                      child: AppImage.widget(avatarUrl!, fit: BoxFit.cover),
                    ),
            ),
            SizedBox(height: _u * 0.04),
            Text(
              petName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: _u * 0.07,
                fontWeight: FontWeight.w700,
                color: AppColors.ink,
              ),
            ),
            SizedBox(height: _u * 0.012),
            Text(
              age.months < 12
                  ? l10n.ageCardRealAgeMonths(age.months)
                  : l10n.ageCardRealAgeYearsMonths(age.years, age.monthsPart),
              style: TextStyle(fontSize: _u * 0.042, color: AppColors.textSecondary),
            ),
            SizedBox(height: _u * 0.055),
            // 主视觉：当量数字。「≈」不可省 —— 这是估算，写成等号会被当成精确结论。
            Text(
              l10n.ageCardHumanYears(age.humanAge),
              key: const ValueKey('ageCardHumanYears'),
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: _u * 0.105,
                height: 1.1,
                fontWeight: FontWeight.w800,
                color: AppColors.mint,
              ),
            ),
            SizedBox(height: _u * 0.045),
            Text(
              quip,
              key: const ValueKey('ageCardQuip'),
              textAlign: TextAlign.center,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: _u * 0.05,
                height: 1.35,
                color: AppColors.textSecondary,
              ),
            ),
            if (sizeLabel != null) ...[
              SizedBox(height: _u * 0.045),
              Container(
                key: const ValueKey('ageCardSizeBand'),
                padding: EdgeInsets.symmetric(
                    horizontal: _u * 0.04, vertical: _u * 0.014),
                decoration: BoxDecoration(
                  color: AppColors.cream2,
                  borderRadius: BorderRadius.circular(_u * 0.06),
                ),
                child: Text(
                  sizeLabel!,
                  style: TextStyle(fontSize: _u * 0.04, color: AppColors.ink2),
                ),
              ),
            ],
          ],
          ),
          ),
        ),
      );

  Widget _brandBand(AppLocalizations l10n) => Container(
        key: const ValueKey('ageCardBrandBand'),
        padding: EdgeInsets.symmetric(horizontal: _pad * 1.6, vertical: _pad),
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  SvgPicture.asset(
                    'assets/brand/wordmark_brand.svg',
                    width: _u * 0.224,
                    // 字标资产是纯白的（给紫底启动页做的）；白底卡上不上色就是白字画在白纸上。
                    colorFilter: const ColorFilter.mode(AppColors.mint, BlendMode.srcIn),
                  ),
                  SizedBox(height: _u * 0.029),
                  Text(
                    l10n.shareCardScanHint,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: _u * 0.043, color: AppColors.muted),
                  ),
                ],
              ),
            ),
            // 🔴 年龄卡的码指向**通用下载页**，不是某条内容的分享链接，也**不带 token**
            // （AD-A19）：这张图会被发给陌生人看，任何随图外流的标识都是隐患。
            // 地址走配置项 `kAppDownloadUrl`，不硬编码在这里。
            CardQr(data: kAppDownloadUrl, side: math.max(CardQr.minExportSide, _u * 0.229)),
          ],
        ),
      );
}
