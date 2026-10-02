import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../content/presentation/share_card/share_card_preview_page.dart';
import '../../../content/presentation/share_card/share_card_skeleton.dart';
import '../../../keepsake/presentation/keepsake_share_reward.dart';
import '../../../profile/domain/card_link.dart';
import '../../data/passport_share_reward_repository.dart';
import '../../domain/passport_share_grid.dart';
import '../../domain/passport_snapshot.dart';
import '../../domain/pet_passport.dart';
import '../passport_page_face.dart';
import '../../../keepsake/presentation/keepsake_card_style.dart';

/// 护照分享卡的纯数据（V1.3.2 Story 4.3）：实时护照与已购快照两种来源都转成它，卡面不直接依赖接口 DTO。
class PassportShareData {
  const PassportShareData({required this.petName, required this.passportNo, required this.stamps});

  /// 实时护照（B2 单章页「Bagikan」）。
  factory PassportShareData.fromPassport(PetPassport p) =>
      PassportShareData(petName: p.petName, passportNo: p.passportNo, stamps: p.stamps);

  /// 已购版本（回看页「Bagikan」）：章列表与章数**全部取快照**，不读实时数据（AD-7）。
  /// 快照→章的转换沿用 3.4 回看页同一份（[PassportSnapshotDetail.fromJson] → [PassportStamp]）。
  factory PassportShareData.fromSnapshot(PassportSnapshotDetail d) =>
      PassportShareData(petName: d.petName, passportNo: d.passportNo, stamps: d.stamps);

  final String petName;

  /// 12 位连写；卡上单独一行、不截断（C-13）。
  final String passportNo;
  final List<PassportStamp> stamps;

  /// 本次卡面代表的章数（= 埋点 `stamp_count`）。
  int get stampCount => stamps.length;
}

/// 护照分享卡（V1.3.2 Story 4.3 · UI 稿 B8）：通用骨架的一种用法。
///
/// - 主体段：护照内页（页眉宠物名 + 12 位护照号单独一行 + 章格），**不裁切** ——
///   护照内页是带字段的版面，cover 会裁掉护照号或章；章格按主体段实际尺寸自适应排满，
///   效果等同 contain 居中（留白处为护照页底色）；
/// - 信息段：「Paspor {pet} · {N} cap」+ 护照号（单独一行，C-13）；
/// - 品牌段：骨架自带（字标 + 扫码提示 + `/get` 下载二维码）。
class PassportShareCard extends StatelessWidget {
  const PassportShareCard({super.key, required this.data, required this.canvas});

  final PassportShareData data;
  final CardCanvas canvas;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final m = ShareCardMetrics(canvas);
    final u = m.u;
    return ShareCardSkeleton(
      canvas: canvas,
      color: Colors.white,
      qrData: petDownloadUrl(),
      mainAreaKey: const ValueKey('passportShareCardMain'),
      infoAreaKey: const ValueKey('passportShareCardInfo'),
      main: ColoredBox(
        color: AppColors.cream2,
        child: Padding(
          padding: EdgeInsets.fromLTRB(m.pad, m.pad, m.pad, m.pad * 0.6),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(data.petName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: u * 0.06, fontWeight: FontWeight.w800, color: AppColors.ink)),
              SizedBox(height: u * 0.008),
              // 12 位护照号：单独一行、等宽数字；放不下时整体缩小而不是截断。
              Align(
                alignment: Alignment.centerLeft,
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(data.passportNo,
                      key: const ValueKey('passportShareCardNo'),
                      softWrap: false,
                      style: TextStyle(
                          fontSize: u * 0.045,
                          letterSpacing: u * 0.004,
                          color: AppColors.ink2,
                          fontFeatures: const [FontFeature.tabularFigures()])),
                ),
              ),
              SizedBox(height: m.pad * 0.6),
              Expanded(child: _StampGrid(stamps: data.stamps, canvas: canvas)),
            ],
          ),
        ),
      ),
      info: Padding(
        padding: EdgeInsets.fromLTRB(m.pad, m.pad * 0.55, m.pad, m.pad * 0.4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '${l10n.passportShareInfoTitle(data.petName)} · ${l10n.passportStampCount(data.stampCount)}',
              key: const ValueKey('passportShareCardInfoTitle'),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: u * 0.058, fontWeight: FontWeight.w800, color: AppColors.ink),
            ),
            SizedBox(height: u * 0.01),
            FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text(data.passportNo,
                  softWrap: false,
                  style: TextStyle(
                      fontSize: u * 0.044,
                      letterSpacing: u * 0.003,
                      color: AppColors.ink2,
                      fontFeatures: const [FontFeature.tabularFigures()])),
            ),
          ],
        ),
      ),
      infoMinOfRest: 0.12,
    );
  }
}

/// 章格：按 [passportShareGrid] 自适应列数；放不下时末格「+N」。只画已集的章，不画空位、不写分母。
class _StampGrid extends StatelessWidget {
  const _StampGrid({required this.stamps, required this.canvas});

  final List<PassportStamp> stamps;
  final CardCanvas canvas;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      const gap = 24.0;
      final g = passportShareGrid(
        width: c.maxWidth,
        height: c.maxHeight,
        count: stamps.length,
        minCellWidth: canvas.width / 6,
        gap: gap,
      );
      if (g.columns == 0) return const SizedBox.shrink();
      final cells = <Widget>[
        for (final s in stamps.take(g.visibleStamps))
          SizedBox(
            width: g.cellWidth,
            height: g.cellHeight,
            child: PassportStampCell(
              stamp: s,
              stampSize: g.cellWidth * 0.78,
              labelFontSize: g.cellWidth * 0.11,
            ),
          ),
        if (g.overflowCount > 0)
          SizedBox(
            key: const ValueKey('passportShareCardOverflow'),
            width: g.cellWidth,
            height: g.cellHeight,
            child: Center(
              child: Container(
                width: g.cellWidth * 0.78,
                height: g.cellWidth * 0.78,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: AppColors.violet100,
                  border: Border.all(color: AppColors.lineViolet, width: g.cellWidth * 0.02),
                ),
                child: Text('+${g.overflowCount}',
                    style: TextStyle(
                        fontSize: g.cellWidth * 0.22,
                        fontWeight: FontWeight.w800,
                        color: AppColors.mint,
                        fontFeatures: const [FontFeature.tabularFigures()])),
              ),
            ),
          ),
      ];
      return Align(
        alignment: Alignment.topCenter,
        child: Wrap(spacing: gap, runSpacing: gap, alignment: WrapAlignment.center, children: cells),
      );
    });
  }
}

/// 护照卡预览入口：B2 单章页「Bagikan」（实时，水印看当前版本是否已买）与已购版本回看页「Bagikan」（快照，恒无水印）共用。
///
/// 🔴 水印**只读服务端字段**（实时 = `currentVersionUnlocked`；快照 = 已付版本，恒 false），客户端不算 hash（AD-7 / AD-14）。
/// 埋点 `passport_card_shared {card_type: page, stamp_count}` 只在系统分享面板回调成功后上报；不带护照号 / 场所名 / token / 宠物名。
/// 同一时机上报领奖（Story 4.5，PAGE；已购版本重新导出同样计），失败当 0、没发静默。
Future<void> openPassportSharePreview(BuildContext context, WidgetRef ref,
    {required PassportShareData data, required bool watermarked}) {
  return Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (routeCtx) => ShareCardPreviewPage.custom(
      watermarked: watermarked,
      watermarkOpacity: kKeepsakeWatermarkOpacity,
      exportName: 'tailtopia_passport',
      onGenerated: (ms) => Analytics.capture(kKeepsakeCardGeneratedEvent, {'card_type': 'page', 'duration_ms': ms}),
      builder: (canvas) => PassportShareCard(data: data, canvas: canvas),
      onShared: (_) {
        // 待确认 4.6（2026-10-02）：加 card_type 区分护照卡 / 登机牌卡。
        Analytics.capture('passport_card_shared', {'card_type': 'page', 'stamp_count': data.stampCount});
        unawaited(claimKeepsakeShareReward(routeCtx, ref,
            () => ref.read(passportShareRewardRepositoryProvider).reportShareForReward(PassportShareRewardRepository.page)));
      },
    ),
  ));
}
