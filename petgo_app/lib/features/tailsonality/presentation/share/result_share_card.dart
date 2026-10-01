import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../content/presentation/share_card/share_card_preview_page.dart';
import '../../../content/presentation/share_card/share_card_skeleton.dart';
import '../../../profile/data/profile_repository.dart';
import '../../../profile/domain/card_link.dart';
import '../../domain/tailsonality_result.dart';
import '../widgets/ts_result_card.dart';
import 'ts_share_owner_name.dart';

/// Tailsonality 结果分享卡（V1.3.2 Story 4.1 · AC4 · UI 稿 A14）：通用骨架的一种用法。
///
/// - 主体段：Story 2.4 的 3:4 结果卡**卡面**（[TsResultCardFace]）`BoxFit.cover` 铺满 ——
///   同一套卡面内容，3:4 页内 / 9:16 分享两种版式，不另画一套；
/// - 信息段：宠物名（粗）+ 主人昵称（次级色；取不到整行不显示）；
/// - 品牌段：骨架自带（字标 + 扫码提示 + `/get` 下载二维码）。
///
/// 🔴 **结果卡是付费保护卡**：未解锁带水印、解锁无水印（PRD §3.2）。配型卡规则相反，勿统一。
/// 水印由预览页按 `watermarked` 挂在外层 boundary，本组件只画卡面。
class ResultShareCard extends StatelessWidget {
  const ResultShareCard({
    super.key,
    required this.result,
    required this.canvas,
    required this.petName,
    this.ownerName,
  });

  final TailsonalityResult result;
  final CardCanvas canvas;
  final String petName;

  /// 主人昵称；null → 信息段只显示宠物名（不兜底邮箱、不写 Kamu）。
  final String? ownerName;

  @override
  Widget build(BuildContext context) {
    final m = ShareCardMetrics(canvas);
    final u = m.u;
    return ShareCardSkeleton(
      canvas: canvas,
      color: Colors.white,
      qrData: petDownloadUrl(),
      mainAreaKey: const ValueKey('resultShareCardMain'),
      infoAreaKey: const ValueKey('resultShareCardInfo'),
      main: ClipRect(
        child: FittedBox(
          fit: BoxFit.cover,
          child: SizedBox(
            width: kTsCardCanvas.width,
            height: kTsCardCanvas.height,
            child: TsResultCardFace(result: result),
          ),
        ),
      ),
      // 信息段只有两行字：下界保证它不贴着插画，余下全归主体段。
      info: Padding(
        padding: EdgeInsets.fromLTRB(m.pad, m.pad * 0.55, m.pad, m.pad * 0.4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 宠物名取不到（档案未加载）时不画一行空白粗体。
            if (petName.trim().isNotEmpty)
              Text(
                petName,
                key: const ValueKey('resultShareCardPetName'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: u * 0.066, fontWeight: FontWeight.w800, color: AppColors.ink),
              ),
            if (ownerName != null) ...[
              SizedBox(height: u * 0.012),
              Text(
                ownerName!,
                key: const ValueKey('resultShareCardOwner'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: u * 0.046, fontWeight: FontWeight.w500, color: AppColors.ink2),
              ),
            ],
          ],
        ),
      ),
      infoMinOfRest: 0.12,
    );
  }
}

/// 结果卡预览入口：结果页 ⋯「Bagikan」与已解锁底部「Bagikan」**共用**这一个。
///
/// 水印按**服务端解锁态**（`result.unlocked`）：false → 预览与导出都带水印。
/// 埋点 `tailsonality_card_shared` 只在系统分享面板回调成功后报（取消不报），
/// 不带宠物名 / 主人名 / token。
Future<void> openResultSharePreview(BuildContext context, WidgetRef ref, TailsonalityResult result) {
  final petName = ref.read(petProfileProvider).value?.name ?? '';
  final ownerName = tsShareOwnerName(ref);
  return Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (_) => ShareCardPreviewPage.custom(
      watermarked: !result.unlocked,
      exportName: 'tailtopia_tailsonality',
      builder: (canvas) => ResultShareCard(
        result: result,
        canvas: canvas,
        petName: petName,
        ownerName: ownerName,
      ),
      onShared: (_) => Analytics.capture('tailsonality_card_shared', {
        'role_code': result.typeCode,
        'is_unlocked': result.unlocked,
      }),
    ),
  ));
}
