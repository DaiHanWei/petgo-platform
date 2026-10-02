import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_watermark.dart';
import '../../../../shared/utils/date_format.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../place/domain/place_summary.dart';
import '../../../place/presentation/widgets/place_stamp_view.dart';
import '../../domain/boarding_pass.dart';
import '../../../keepsake/presentation/keepsake_card_style.dart';

/// 卡面字段标签：机票版式的固定英文，**不进 ARB**（两语同形，与航空登机牌一致；翻成印尼语反而不像机票）。
abstract final class BoardingPassLabels {
  static const String header = 'BOARDING PASS · TAILTOPIA';
  static const String passenger = 'PASSENGER';
  static const String breed = 'BREED';
  static const String to = 'TO';
  static const String passport = 'PASSPORT';
  static const String date = 'DATE';
  static const String visits = 'VISITS';
  static const String seat = 'SEAT';
}

/// 场所类型 → 包内默认场所图（D-12：7 张素材未到货前一律返回 null 走占位；到一张换一张，只改这里）。
String? boardingPassPlaceImage(PlaceType? type) => null;

/// 水印画布：卡片 3:4.6 版式、圆角与卡一致。
const CardCanvas kBoardingPassCanvas = CardCanvas(size: Size(300, 460), radius: 18);

/// 一张完整登机牌（V1.3.2 Story 3.5 · AC9.1 · B3b / B3c）：卡内顶部图带 + 机票字段。
///
/// 🔴 PASSPORT **单独一行**、12 位完整显示（等宽数字、不截断）—— 与 DATE / VISITS 三栏平分会被截断（UX-DR15）。
/// [watermarked]：未解锁叠品牌水印（挂在卡内容的兄弟节点上）。
class BoardingPassCard extends StatelessWidget {
  const BoardingPassCard({super.key, required this.pass, required this.watermarked});

  final BoardingPassDetail pass;
  final bool watermarked;

  static const TextStyle _label =
      TextStyle(fontSize: 10, fontWeight: FontWeight.w700, letterSpacing: 1.2, color: AppColors.muted);
  static const TextStyle _value = TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: AppColors.ink);
  static const TextStyle _mono = TextStyle(
      fontSize: 15,
      fontWeight: FontWeight.w800,
      letterSpacing: 1.1,
      color: AppColors.ink,
      fontFeatures: [FontFeature.tabularFigures()]);

  @override
  Widget build(BuildContext context) {
    final p = pass;
    final date = p.lastVisitDate == null ? '—' : formatDayMonthYear(context, p.lastVisitDate!);
    return ClipRRect(
      borderRadius: BorderRadius.circular(18),
      child: Stack(
        children: [
          Container(
            key: const ValueKey('boardingPassCard'),
            color: AppColors.card,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                AspectRatio(aspectRatio: 16 / 7, child: _imageBand(p)),
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 14, 18, 18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(BoardingPassLabels.header,
                          style: TextStyle(
                              fontSize: 11, fontWeight: FontWeight.w800, letterSpacing: 1.6, color: AppColors.mint)),
                      const SizedBox(height: 12),
                      Row(children: [
                        Expanded(child: _field(BoardingPassLabels.passenger, p.passenger, _value)),
                        const SizedBox(width: 12),
                        Expanded(child: _field(BoardingPassLabels.breed, p.breed ?? '—', _value)),
                      ]),
                      const SizedBox(height: 10),
                      _field(BoardingPassLabels.to, p.placeName, _value),
                      const SizedBox(height: 10),
                      // PASSPORT 独占一行。
                      _field(BoardingPassLabels.passport, p.passportNo ?? '—', _mono,
                          valueKey: const ValueKey('boardingPassPassportNo'), noWrap: true),
                      const SizedBox(height: 10),
                      Row(children: [
                        Expanded(child: _field(BoardingPassLabels.date, date, _value)),
                        Expanded(child: _field(BoardingPassLabels.visits, '${p.visitCount}', _mono)),
                        Expanded(child: _field(BoardingPassLabels.seat, p.seat, _mono)),
                      ]),
                    ],
                  ),
                ),
              ],
            ),
          ),
          if (watermarked)
            const CardWatermark(
                key: ValueKey('boardingPassWatermark'), canvas: kBoardingPassCanvas, opacity: kKeepsakeWatermarkOpacity),
        ],
      ),
    );
  }

  Widget _field(String label, String value, TextStyle style, {Key? valueKey, bool noWrap = false}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: _label),
        const SizedBox(height: 2),
        Text(value,
            key: valueKey,
            maxLines: noWrap ? 1 : 2,
            softWrap: !noWrap,
            overflow: noWrap ? TextOverflow.visible : TextOverflow.ellipsis,
            style: style),
      ],
    );
  }

  /// 顶部图带（卡的一部分）：场所首图 → 包内默认场所图 → 代码占位（类型图标 + 渐变底）。
  Widget _imageBand(BoardingPassDetail p) {
    final placeholder = Container(
      key: const ValueKey('boardingPassImagePlaceholder'),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint500, AppColors.mint],
        ),
      ),
      alignment: Alignment.center,
      child: Icon(PlaceStampPlaceholder.iconFor(p.placeType), size: 44, color: Colors.white),
    );
    final url = p.placeImageUrl;
    final asset = boardingPassPlaceImage(p.placeType);
    if (url != null) {
      return AppImage.widget(url, fit: BoxFit.cover, thumbWidth: 800, errorBuilder: (_, _, _) => placeholder);
    }
    if (asset != null) {
      return Image.asset(asset, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder);
    }
    return placeholder;
  }
}
