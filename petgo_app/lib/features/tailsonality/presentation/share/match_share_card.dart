import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../content/presentation/brag_post_entry.dart';
import '../../../content/presentation/share_card/share_card_preview_page.dart';
import '../../../content/presentation/share_card/share_card_skeleton.dart';
import '../../../keepsake/presentation/keepsake_share_reward.dart';
import '../../../profile/domain/card_link.dart';
import '../../data/tailsonality_share_reward_repository.dart';
import '../../domain/ts_match_share_data.dart';
import '../widgets/ts_match_card.dart';
import '../widgets/ts_result_card.dart';
import 'ts_share_owner_name.dart';

/// 配型分享卡（V1.3.2 Story 4.2 · UI 稿 A16）：通用骨架的一种用法。
///
/// - 主体段：2.5 页内配型卡的卡面（[TsMatchCardFace]，按档位取双人插画）`BoxFit.cover` 铺满；
/// - 信息段：「{宠物名} × {主人昵称}」→ 双方代号 → 档位名 → 最多 2 条差异句（4/4 为总评首句）；
/// - 品牌段：骨架自带（字标 + 扫码提示 + `/get` 下载二维码）。
///
/// 🔴 **配型卡是免费传播卡，永不带水印** —— 与结果卡（付费保护卡）规则相反且是有意的（PRD §3.2）。
class MatchShareCard extends StatelessWidget {
  const MatchShareCard({super.key, required this.data, required this.canvas});

  final MatchShareCardData data;
  final CardCanvas canvas;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final m = ShareCardMetrics(canvas);
    final u = m.u;
    final owner = data.ownerName;
    return ShareCardSkeleton(
      canvas: canvas,
      color: Colors.white,
      qrData: petDownloadUrl(),
      mainAreaKey: const ValueKey('matchShareCardMain'),
      infoAreaKey: const ValueKey('matchShareCardInfo'),
      main: ClipRect(
        child: FittedBox(
          fit: BoxFit.cover,
          child: SizedBox(
            width: kTsCardCanvas.width,
            height: kTsCardCanvas.height,
            child: TsMatchCardFace(tier: data.tier),
          ),
        ),
      ),
      info: Padding(
        padding: EdgeInsets.fromLTRB(m.pad, m.pad * 0.55, m.pad, m.pad * 0.4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 宠物名取不到（档案未加载）时不拼出「 × 昵称」；两者都没有整行不显示。
            if (_pairLine(l10n, owner) case final pair?) ...[
              _line(
                pair,
                key: const ValueKey('matchShareCardPair'),
                style: TextStyle(fontSize: u * 0.062, fontWeight: FontWeight.w800, color: AppColors.ink),
              ),
              SizedBox(height: u * 0.012),
            ],
            _line(
              // 与名字行同序：宠物在前。
              '${data.petCode} × ${data.ownerType}',
              key: const ValueKey('matchShareCardCodes'),
              style: TextStyle(
                  fontSize: u * 0.046, fontWeight: FontWeight.w700, color: AppColors.mint, letterSpacing: u * 0.002),
            ),
            SizedBox(height: u * 0.02),
            _line(
              data.tierName,
              key: const ValueKey('matchShareCardTier'),
              style: TextStyle(fontSize: u * 0.055, fontWeight: FontWeight.w900, color: AppColors.ink),
            ),
            if (data.lines.isNotEmpty) ...[
              SizedBox(height: u * 0.016),
              Flexible(child: _Lines(lines: data.lines, fontSize: u * 0.046)),
            ],
          ],
        ),
      ),
      infoMinOfRest: 0.12,
    );
  }

  String? _pairLine(AppLocalizations l10n, String? owner) {
    final pet = data.petName.trim();
    if (pet.isEmpty) return owner;
    return owner == null ? pet : l10n.tailsonalityMatchCardPair(pet, owner);
  }

  Widget _line(String text, {required Key key, required TextStyle style}) =>
      Text(text, key: key, maxLines: 1, overflow: TextOverflow.ellipsis, style: style);
}

/// 差异句：按剩余高度收行（`Text` 只认 maxLines、不会按高度自己收住 —— 同帖子卡正文的做法）。
class _Lines extends StatelessWidget {
  const _Lines({required this.lines, required this.fontSize});

  final List<String> lines;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    const lineHeight = 1.4;
    return LayoutBuilder(builder: (context, c) {
      final maxLines = (c.maxHeight / (fontSize * lineHeight)).floor();
      if (maxLines < 1) return const SizedBox.shrink();
      return Align(
        alignment: Alignment.topLeft,
        heightFactor: 1,
        child: Text(
          lines.join('\n'),
          key: const ValueKey('matchShareCardLines'),
          maxLines: maxLines,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: fontSize, height: lineHeight, color: AppColors.ink2),
        ),
      );
    });
  }
}

/// 配型卡预览入口（配型页 3:4 卡点击）。
///
/// 🔴 一律不带水印，**不看结果是否已付费**（本入口不接收、不读取任何付费状态字段；源码扫描测试钉住）。
/// 埋点 `tailsonality_match_card_shared` 只在系统分享面板回调成功后上报，不带宠物名 / 昵称 / token。
///
/// [cardPng]（Story 4.4）：配型页 push 前截好的 **3:4** 配型卡。非空时预览页出主操作「Pamer di postingan」
/// （发帖用 3:4，不用 9:16：0.5625 会被信息流二次裁切），「Bagikan ke Story」自动降为次按钮。
Future<void> openMatchSharePreview(
  BuildContext context,
  WidgetRef ref, {
  required String petName,
  required String petCode,
  required String ownerType,
  Uint8List? cardPng,
}) {
  final data = MatchShareCardData.from(
    petName: petName,
    ownerName: tsShareOwnerName(ref),
    petCode: petCode,
    ownerType: ownerType,
    locale: Localizations.localeOf(context),
  );
  return Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (routeCtx) => ShareCardPreviewPage.custom(
      watermarked: false,
      exportName: 'tailtopia_tailsonality_match',
      primaryAction: cardPng == null
          ? null
          : ShareCardPreviewAction(
              key: const ValueKey('matchPreviewBrag'),
              label: AppLocalizations.of(routeCtx).bragPostButton,
              onPressed: () => BragPostEntry.open(routeCtx,
                  cardPng: cardPng, text: AppLocalizations.of(routeCtx).tailsonalityBragMatchText),
            ),
      builder: (canvas) => MatchShareCard(data: data, canvas: canvas),
      onShared: (_) {
        Analytics.capture('tailsonality_match_card_shared', {
          'owner_type': data.ownerType,
          'pet_type': data.petType,
          'match_level': data.sameCount,
        });
        // Story 4.5：同一时机上报领奖（MATCH），失败当 0、没发静默。
        unawaited(claimKeepsakeShareReward(routeCtx, ref,
            () => ref.read(tailsonalityShareRewardRepositoryProvider).reportShareForReward(TailsonalityShareRewardRepository.match)));
      },
    ),
  ));
}
