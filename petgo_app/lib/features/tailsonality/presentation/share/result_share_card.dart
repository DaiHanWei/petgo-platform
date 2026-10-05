import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_qr.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../content/presentation/share_card/share_card_preview_page.dart';
import '../../../keepsake/presentation/keepsake_share_reward.dart';
import '../../../profile/data/profile_repository.dart';
import '../../../profile/domain/card_link.dart';
import '../../data/tailsonality_share_reward_repository.dart';
import '../../data/ts_role_art.dart';
import '../../domain/tailsonality_result.dart';
import '../widgets/ts_result_card.dart';
import 'ts_share_owner_name.dart';
import '../../../keepsake/presentation/keepsake_card_style.dart';

/// 结果分享卡画布（2026-10-05 设计稿 `share- example.png`，1217×1081）：横版，**不是**通用骨架的 9:16。
///
/// 左 = 3:4 角色卡（1080 高 → 810 宽），中间一条浅紫竖条，右 = 深紫渐变信息栏。
const CardCanvas kTsShareCanvas = CardCanvas(size: Size(1216, 1080), radius: 32);

/// 版面尺寸（画布坐标 = 导出像素，按设计稿量）。
const double _kArtWidth = 810;
const double _kStripWidth = 20;
const double _kQrSide = 200;
const Color _kStrip = Color(0xFFCABBFF);
const Color _kPanelTop = Color(0xFF3C2D50);
const Color _kPanelBottom = Color(0xFF2A213A);

/// Tailsonality 结果分享卡（V1.3.2 Story 4.1 · 2026-10-05 按设计稿重做）。
///
/// - 左：Story 2.4 的结果卡**卡面**（[TsResultCardFace]，角色卡按需下载）原样放入，不另画一套；
/// - 右栏自上而下：宠物名 / 「x」/ 主人昵称 → 下载二维码（`/get`）+「Download page」→ 品牌字标；
///   主人昵称取不到时只留宠物名（不写「x」、不兜底邮箱、不写 Kamu）。
///
/// 🔴 **结果卡是付费保护卡**：未解锁带水印、解锁无水印（PRD §3.2）。配型卡规则相反，勿统一。
/// 水印由预览页按 `watermarked` 挂在外层 boundary（盖住整张卡），本组件只画卡面。
class ResultShareCard extends StatelessWidget {
  const ResultShareCard({
    super.key,
    required this.result,
    required this.petName,
    this.ownerName,
  });

  final TailsonalityResult result;
  final String petName;

  /// 主人昵称；null → 右栏只显示宠物名。
  final String? ownerName;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pet = petName.trim();
    final owner = ownerName?.trim() ?? '';
    final names = <(String, String)>[
      if (pet.isNotEmpty) ('resultShareCardPetName', pet),
      if (pet.isNotEmpty && owner.isNotEmpty) ('resultShareCardCross', 'x'),
      if (owner.isNotEmpty) ('resultShareCardOwner', owner),
    ];
    const nameStyle = TextStyle(
      fontSize: 52,
      height: 1.25,
      fontWeight: FontWeight.w800, // Poppins 只打包到 800（ExtraBold），写 900 会静默回落。
      color: Colors.white,
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(kTsShareCanvas.radius),
      child: ColoredBox(
        color: Colors.white,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              key: const ValueKey('resultShareCardMain'),
              width: _kArtWidth,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(kTsShareCanvas.radius),
                child: FittedBox(
                  fit: BoxFit.cover,
                  child: SizedBox(
                    width: kTsCardCanvas.width,
                    height: kTsCardCanvas.height,
                    child: TsResultCardFace(result: result),
                  ),
                ),
              ),
            ),
            const SizedBox(width: _kStripWidth, child: ColoredBox(color: _kStrip)),
            Expanded(
              child: DecoratedBox(
                key: const ValueKey('resultShareCardInfo'),
                decoration: const BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [_kPanelTop, _kPanelBottom],
                  ),
                ),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(36, 120, 36, 84),
                  child: Column(
                    children: [
                      for (final (key, line) in names)
                        Text(
                          line,
                          key: ValueKey(key),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          textAlign: TextAlign.center,
                          style: nameStyle,
                        ),
                      const Spacer(),
                      CardQr(data: petDownloadUrl(), side: _kQrSide),
                      const SizedBox(height: 20),
                      Text(
                        l10n.tsShareDownloadHint,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 36, fontWeight: FontWeight.w700, color: Colors.white),
                      ),
                      const Spacer(),
                      // 字标资产本身是纯白（紫底专用），这里正好是深紫底，不上色。
                      SvgPicture.asset('assets/brand/wordmark_brand.svg', width: 190),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 结果卡预览入口：结果页 ⋯「Bagikan」与已解锁底部「Bagikan」**共用**这一个。
///
/// 水印按**服务端解锁态**（`result.unlocked`）：false → 预览与导出都带水印。
/// 埋点 `tailsonality_card_shared` 只在系统分享面板回调成功后报（取消不报），
/// 不带宠物名 / 主人名 / token。同一时机上报领奖（Story 4.5，RESULT），失败当 0、没发静默。
///
/// 角色卡是按需下载的：进预览前先确保这张图已到手（结果页通常已下好，这里是内存命中）。
/// 取不到就提示重试、不进预览 —— 不让用户把占位卡分享出去。
Future<void> openResultSharePreview(BuildContext context, WidgetRef ref, TailsonalityResult result) async {
  final petName = ref.read(petProfileProvider).value?.name ?? '';
  final ownerName = tsShareOwnerName(ref);
  final art = await TsRoleArt.load(result.letters);
  if (!context.mounted) return;
  if (art == null) {
    showAppToast(context, AppLocalizations.of(context).detailNetworkError);
    return;
  }
  // 预解码，预览第一帧就是真图（不等）。解不出来静默 —— 卡面自己会回落占位。
  unawaited(precacheImage(MemoryImage(art), context, onError: (_, _) {}));
  await Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (routeCtx) => ShareCardPreviewPage.custom(
      canvas: kTsShareCanvas,
      watermarked: !result.unlocked,
      watermarkOpacity: kKeepsakeWatermarkOpacity,
      exportName: 'tailtopia_tailsonality',
      onGenerated: (ms) => Analytics.capture(kKeepsakeCardGeneratedEvent, {'card_type': 'result', 'duration_ms': ms}),
      builder: (_) => ResultShareCard(
        result: result,
        petName: petName,
        ownerName: ownerName,
      ),
      onShared: (_) {
        Analytics.capture('tailsonality_card_shared', {
          'role_code': result.typeCode,
          'is_unlocked': result.unlocked,
        });
        unawaited(claimKeepsakeShareReward(routeCtx, ref,
            () => ref.read(tailsonalityShareRewardRepositoryProvider).reportShareForReward(TailsonalityShareRewardRepository.result)));
      },
    ),
  ));
}
