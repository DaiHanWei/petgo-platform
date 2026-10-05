import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_qr.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../content/presentation/brag_post_entry.dart';
import '../../../content/presentation/share_card/share_card_preview_page.dart';
import '../../../keepsake/presentation/keepsake_share_reward.dart';
import '../../../profile/domain/card_link.dart';
import '../../data/tailsonality_share_reward_repository.dart';
import '../../data/ts_remote_art.dart';
import '../../domain/ts_match_share_data.dart';
import '../widgets/ts_match_card.dart';
import 'ts_share_owner_name.dart';
import '../../../keepsake/presentation/keepsake_card_style.dart';

/// 配型分享卡画布（2026-10-05 设计稿 `5-level-match-cards/example.png`，1080×1441）：
/// 上 1:1 配型卡（1080×1080）、一条浅紫横条、下深紫信息栏。**不是**通用骨架的 9:16。
const CardCanvas kTsMatchShareCanvas = CardCanvas(size: Size(1080, 1440), radius: 32);

/// 版面尺寸（画布坐标 = 导出像素，按设计稿量）。
const double _kStripHeight = 20;
const Color _kStrip = Color(0xFFCABBFF);
const Color _kPanelTop = Color(0xFF3D2E51);
const Color _kPanelBottom = Color(0xFF2B223B);

/// 配型分享卡（V1.3.2 Story 4.2 · 2026-10-05 按设计稿重做）。
///
/// - 上：2.5 页内配型卡的卡面（[TsMatchCardFace]，按档位取双人插画；档位名 / 文案 / 刻度条都画在图里）；
/// - 下栏：左「{宠物名} x {主人昵称}」+ 品牌字标，右 `/get` 下载码 +「Download page」。
///
/// 🔴 **配型卡是免费传播卡，永不带水印** —— 与结果卡（付费保护卡）规则相反且是有意的（PRD §3.2）。
class MatchShareCard extends StatelessWidget {
  const MatchShareCard({super.key, required this.data});

  final MatchShareCardData data;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pair = _pairLine(l10n, data.ownerName);
    return ClipRRect(
      borderRadius: BorderRadius.circular(kTsMatchShareCanvas.radius),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            key: const ValueKey('matchShareCardMain'),
            width: kTsMatchCanvas.width,
            height: kTsMatchCanvas.height,
            child: TsMatchCardFace(tier: data.tier),
          ),
          const SizedBox(height: _kStripHeight, child: ColoredBox(color: _kStrip)),
          Expanded(
            child: DecoratedBox(
              key: const ValueKey('matchShareCardInfo'),
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [_kPanelTop, _kPanelBottom],
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(68, 40, 54, 40),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // 宠物名取不到（档案未加载）时不拼出「 x 昵称」；两者都没有整行不显示。
                          if (pair != null)
                            Text(
                              pair,
                              key: const ValueKey('matchShareCardPair'),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 56,
                                height: 1.2,
                                // Poppins 只打包到 800（ExtraBold），写 900 会静默回落。
                                fontWeight: FontWeight.w800,
                                color: Colors.white,
                              ),
                            ),
                          const Spacer(),
                          // 字标资产本身是纯白（紫底专用），这里正好是深紫底，不上色。
                          // 2026-10-05 产品定：字标宽 240px，导出图上字标最底一行像素在 y=1400
                          // （栏底内边距 40 让盒底落在 1400，字形底再下压 1px 对齐）。改这里先导出量一次。
                          Transform.translate(
                            offset: const Offset(0, 1),
                            child: SvgPicture.asset('assets/brand/wordmark_brand.svg', width: 240),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 24),
                    Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        // 设计稿码约 124px，低于可扫底线 —— 按 CardQr.minExportSide 出，宁大勿扫不出。
                        CardQr(data: petDownloadUrl(), side: CardQr.minExportSide),
                        const SizedBox(height: 12),
                        Text(
                          l10n.tsShareDownloadHint,
                          maxLines: 1,
                          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w700, color: Colors.white),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  String? _pairLine(AppLocalizations l10n, String? owner) {
    final pet = data.petName.trim();
    if (pet.isEmpty) return owner;
    return owner == null ? pet : l10n.tailsonalityMatchCardPair(pet, owner);
  }
}

/// 配型卡预览入口（配型页 3:4 卡点击）。
///
/// 🔴 一律不带水印，**不看结果是否已付费**（本入口不接收、不读取任何付费状态字段；源码扫描测试钉住）。
/// 埋点 `tailsonality_match_card_shared` 只在系统分享面板回调成功后上报，不带宠物名 / 昵称 / token。
///
/// [cardPng]（Story 4.4）：配型页 push 前截好的 **1:1** 配型卡。非空时预览页出主操作「Pamer di postingan」
/// （发帖用纯配型卡，不用带信息栏的分享卡），「Bagikan ke Story」自动降为次按钮。
///
/// 配型卡是按需下载的：进预览前先确保这张图已到手（配型页通常已下好，这里是内存命中）；
/// 取不到就提示重试、不进预览 —— 不让用户把占位卡分享出去。
Future<void> openMatchSharePreview(
  BuildContext context,
  WidgetRef ref, {
  required String petName,
  required String petCode,
  required String ownerType,
  Uint8List? cardPng,
}) async {
  final data = MatchShareCardData.from(
    petName: petName,
    ownerName: tsShareOwnerName(ref),
    petCode: petCode,
    ownerType: ownerType,
    locale: Localizations.localeOf(context),
  );
  final art = await TsRemoteArt.load(TsRemoteArt.match(data.tier));
  if (!context.mounted) return;
  if (art == null) {
    showAppToast(context, AppLocalizations.of(context).detailNetworkError);
    return;
  }
  // 预解码，预览第一帧就是真图（不等）。解不出来静默 —— 卡面自己会回落占位。
  unawaited(precacheImage(MemoryImage(art), context, onError: (_, _) {}));
  await Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (routeCtx) => ShareCardPreviewPage.custom(
      canvas: kTsMatchShareCanvas,
      watermarked: false,
      exportName: 'tailtopia_tailsonality_match',
      onGenerated: (ms) => Analytics.capture(kKeepsakeCardGeneratedEvent, {'card_type': 'match', 'duration_ms': ms}),
      primaryAction: cardPng == null
          ? null
          : ShareCardPreviewAction(
              key: const ValueKey('matchPreviewBrag'),
              label: AppLocalizations.of(routeCtx).bragPostButton,
              onPressed: () {
                BragPostEntry.reportTap(BragPostSource.matchPreview);
                BragPostEntry.open(routeCtx,
                    cardPng: cardPng, text: AppLocalizations.of(routeCtx).tailsonalityBragMatchText);
              },
            ),
      builder: (_) => MatchShareCard(data: data),
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
