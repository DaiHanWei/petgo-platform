import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../content/presentation/share_card/share_card_preview_page.dart';
import '../../../content/presentation/share_card/share_card_skeleton.dart';
import '../../../profile/domain/card_link.dart';
import '../../data/boarding_pass_repository.dart';
import '../../domain/boarding_pass.dart';
import '../widgets/boarding_pass_card.dart';

/// 登机牌分享卡（V1.3.2 Story 4.3 · UI 稿 B9）：通用骨架的一种用法。
///
/// - 主体段：3.5 的整张登机牌卡（[BoardingPassCard]，场所图带 + 机票字段，PASSPORT 单独一行），
///   **contain 居中**（不 cover —— 会裁掉 SEAT / 护照号）；留白为卡外底色。
///   卡内不叠水印：水印由预览页按该张解锁态挂在整张 9:16 卡外层（导出同样带）。
/// - 信息段：「{pet} · {场所名}」/「{日期} · {次数}×」（日期与卡上 DATE 同一取值 `lastVisitDate`、同一格式）。
/// - 品牌段：骨架自带。
///
/// 场所已下架照样可分享、场所名照常显示（下架只影响详情页地址与跳转，AD-5）。
class BoardingPassShareCard extends StatelessWidget {
  const BoardingPassShareCard({super.key, required this.pass, required this.canvas});

  final BoardingPassDetail pass;
  final CardCanvas canvas;

  /// 登机牌卡的排版宽度（逻辑单位；与详情页里卡的常见宽度同档，contain 时整体放大）。
  static const double _passWidth = 340;

  @override
  Widget build(BuildContext context) {
    final m = ShareCardMetrics(canvas);
    final u = m.u;
    final date = pass.lastVisitDate == null ? '—' : formatDayMonthYear(context, pass.lastVisitDate!);
    return ShareCardSkeleton(
      canvas: canvas,
      color: Colors.white,
      qrData: petDownloadUrl(),
      mainAreaKey: const ValueKey('boardingPassShareCardMain'),
      infoAreaKey: const ValueKey('boardingPassShareCardInfo'),
      main: ColoredBox(
        color: AppColors.cream2,
        child: Padding(
          padding: EdgeInsets.all(m.pad),
          child: FittedBox(
            fit: BoxFit.contain,
            child: SizedBox(width: _passWidth, child: BoardingPassCard(pass: pass, watermarked: false)),
          ),
        ),
      ),
      info: Padding(
        padding: EdgeInsets.fromLTRB(m.pad, m.pad * 0.55, m.pad, m.pad * 0.4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${pass.passenger} · ${pass.placeName}',
                key: const ValueKey('boardingPassShareCardTitle'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: u * 0.058, fontWeight: FontWeight.w800, color: AppColors.ink)),
            SizedBox(height: u * 0.01),
            Text('$date · ${pass.visitCount}×',
                key: const ValueKey('boardingPassShareCardMeta'),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: u * 0.046,
                    color: AppColors.ink2,
                    fontFeatures: const [FontFeature.tabularFigures()])),
          ],
        ),
      ),
      infoMinOfRest: 0.12,
    );
  }
}

/// 登机牌卡预览入口（详情页顶栏分享按钮，B3b / B3c 两态都有）。
///
/// 🔴 水印按**该张**登机牌的服务端解锁态（详情 DTO `unlocked`）：false 带、true 无；与护照快照判定无关。
/// 埋点 `passport_card_shared {stamp_count}`：stamp_count = 该宠物当前总章数（取登机牌列表条目数 =
/// 打过卡的场所数）。预览**立即打开**，章数在后台取（从订单详情等处进来时列表未加载）；
/// 分享成功那一刻仍未取到则不带该属性。不带护照号 / 场所名 / token / 宠物名。
Future<void> openBoardingPassSharePreview(BuildContext context, WidgetRef ref, BoardingPassDetail pass) {
  int? stampCount = ref.read(boardingPassListProvider).value?.items.length;
  if (stampCount == null) {
    unawaited(_fetchStampCount(ref).then((n) => stampCount = n));
  }
  return Navigator.of(context).push(MaterialPageRoute<void>(
    builder: (_) => ShareCardPreviewPage.custom(
      watermarked: !pass.unlocked,
      exportName: 'tailtopia_boarding_pass',
      builder: (canvas) => BoardingPassShareCard(pass: pass, canvas: canvas),
      onShared: (_) => Analytics.capture('passport_card_shared', {'stamp_count': ?stampCount}),
    ),
  ));
}

Future<int?> _fetchStampCount(WidgetRef ref) async {
  try {
    final list = await ref.read(boardingPassListProvider.future).timeout(const Duration(seconds: 10));
    return list.items.length;
  } catch (_) {
    return null;
  }
}
