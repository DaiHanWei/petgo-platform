import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_watermark.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../keepsake/presentation/keepsake_card_style.dart';
import '../../../place/domain/place_summary.dart';
import '../../../place/presentation/widgets/place_stamp_view.dart';
import '../../domain/boarding_pass.dart';

/// 卡面字段标签：机票版式的固定英文，**不进 ARB**（两语同形，与航空登机牌一致；翻成印尼语反而不像机票）。
/// 大小写照 2026-10-06 设计稿（字段名首字母大写，页眉 / SEAT 全大写）。
abstract final class BoardingPassLabels {
  static const String header = 'BOARDING PASS';
  static const String passenger = 'Passenger';
  static const String breed = 'Breed';
  static const String to = 'To';
  static const String passport = 'Passport';
  static const String date = 'Date';
  static const String visits = 'Visits';
  static const String seat = 'SEAT';

  /// 没有护照号时的占位（产品：用「——」表示）。
  static const String noPassport = '——';
}

/// 场所类型 → 包内默认场所图（D-12：素材未到货前一律返回 null 走占位；到一张换一张，只改这里）。
String? boardingPassPlaceImage(PlaceType? type) => null;

/// 卡面画布 = 设计稿原尺寸（`boarding ticket/Background.png` 1754×780，横版票）。
/// 所有坐标、字号都按这个画布写，外层 [FittedBox] 等比缩放。
const CardCanvas kBoardingPassCanvas = CardCanvas(size: Size(1754, 780), radius: 50);

/// 一张完整登机牌（2026-10-06 按设计稿重做：横版票面 + 邮票框场所照 + 票根场所章与座位号）。
///
/// 字段取值（产品口径）：
/// - 照片 = 打卡场所的第一张图（`placeImageUrl`）；没有 → 包内默认场所图 → 类型图标占位
/// - Passenger = 宠物名；Breed = 档案品种原文
/// - To = 打卡的场所名
/// - Date = **最近一次**打卡日（`lastVisitDate`，「12 SEP 2026」大写月）
/// - Passport = 护照号；没有护照 → 「——」
/// - Visits = 到访次数「2x」；票根：场所章（专属章优先，否则按类型默认章）+ SEAT
///
/// [watermarked]：未解锁叠品牌水印，**按票面形状裁切**（不盖到缺口与圆角外）。
class BoardingPassCard extends StatelessWidget {
  const BoardingPassCard({super.key, required this.pass, required this.watermarked});

  final BoardingPassDetail pass;
  final bool watermarked;

  static const Color _labelColor = Color(0xFF754C24);
  static const Color _valueColor = Color(0xFF42210B);

  // Rubik 行高锁 1.0 时：字框顶 → 大写字母顶 ≈ 0.089em（ascent 0.789em − capHeight 0.70em），
  // 下面各行的 top 都是「设计稿量出的大写字母顶 − 0.089×字号」。
  static const double _labelSize = 33;
  static const double _valueSize = 47;

  static TextStyle _rubik(double size, FontWeight weight, Color color, {double letterSpacing = 0}) => TextStyle(
        fontFamily: 'Rubik',
        fontSize: size,
        height: 1.0,
        fontWeight: weight,
        fontVariations: [FontVariation('wght', weight.value.toDouble())],
        letterSpacing: letterSpacing,
        color: color,
        // 卡面可能挂在没有 Material 祖先的子树里（导出 / 预览），显式关掉兜底下划线。
        decoration: TextDecoration.none,
      );

  static double _topFor(double capTop, double size) => capTop - 0.089 * size;

  @override
  Widget build(BuildContext context) {
    final p = pass;
    final locale = Localizations.localeOf(context).languageCode == 'id' ? 'id' : 'en';
    final date = p.lastVisitDate == null
        ? '—'
        : DateFormat('d MMM yyyy', locale).format(p.lastVisitDate!).toUpperCase();
    final label = _rubik(_labelSize, FontWeight.w400, _labelColor);
    final value = _rubik(_valueSize, FontWeight.w700, _valueColor);

    Widget text(String s, TextStyle style, {Key? key, TextAlign align = TextAlign.left}) => Text(s,
        key: key, maxLines: 1, softWrap: false, overflow: TextOverflow.ellipsis, textAlign: align, style: style);

    return AspectRatio(
      key: const ValueKey('boardingPassCard'),
      aspectRatio: kBoardingPassCanvas.aspectRatio,
      child: FittedBox(
        fit: BoxFit.contain,
        child: SizedBox(
          width: kBoardingPassCanvas.width,
          height: kBoardingPassCanvas.height,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned.fill(child: Image.asset('assets/boarding_pass/ticket_bg.png', fit: BoxFit.fill)),
              // ---- 左：邮票框（实心紫底 + 齿孔）垫底，场所照叠在框内 384×562 ----
              Positioned(
                left: 84,
                top: 72,
                width: 444,
                height: 634,
                child: Image.asset('assets/boarding_pass/photo_frame.png', fit: BoxFit.fill),
              ),
              Positioned(left: 114, top: 108, width: 384, height: 562, child: _photo(p)),
              // ---- 中：字标 + 页眉 ----
              Positioned(
                left: 560,
                top: 55,
                width: 210,
                height: 65,
                child: Image.asset('assets/boarding_pass/logo.png', fit: BoxFit.contain),
              ),
              Positioned(
                right: 1754 - 1330,
                top: _topFor(90, 34),
                child: Text(BoardingPassLabels.header,
                    style: _rubik(34, FontWeight.w400, _labelColor, letterSpacing: 3.6)),
              ),
              // ---- 中：字段 ----
              Positioned(left: 575, top: _topFor(171, _labelSize), child: text(BoardingPassLabels.passenger, label)),
              Positioned(
                left: 575,
                width: 420,
                top: _topFor(214, _valueSize),
                child: text(p.passenger, value, key: const ValueKey('boardingPassPassenger')),
              ),
              Positioned(
                right: 1754 - 1328,
                top: _topFor(171, _labelSize),
                child: text(BoardingPassLabels.breed, label, align: TextAlign.right),
              ),
              Positioned(
                right: 1754 - 1328,
                width: 330,
                top: _topFor(214, _valueSize),
                child: text(p.breed ?? '—', value, align: TextAlign.right),
              ),
              Positioned(left: 575, top: _topFor(299, _labelSize), child: text(BoardingPassLabels.to, label)),
              Positioned(
                left: 575,
                width: 760,
                top: _topFor(339, _valueSize),
                child: text(p.placeName, value, key: const ValueKey('boardingPassTo')),
              ),
              Positioned(left: 575, top: _topFor(429, _labelSize), child: text(BoardingPassLabels.passport, label)),
              Positioned(
                left: 575,
                width: 760,
                top: _topFor(471, _valueSize),
                child: text(p.passportNo ?? BoardingPassLabels.noPassport,
                    value.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
                    key: const ValueKey('boardingPassPassportNo')),
              ),
              Positioned(left: 575, top: _topFor(565, _labelSize), child: text(BoardingPassLabels.date, label)),
              Positioned(
                left: 575,
                width: 410,
                top: _topFor(607, _valueSize),
                child: text(date, value, key: const ValueKey('boardingPassDate')),
              ),
              Positioned(left: 1002, top: _topFor(565, _labelSize), child: text(BoardingPassLabels.visits, label)),
              Positioned(
                left: 1002,
                width: 320,
                top: _topFor(607, _valueSize),
                child: text('${p.visitCount}x', value.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
                    key: const ValueKey('boardingPassVisits')),
              ),
              // ---- 右：票根（场所章 + SEAT） ----
              Positioned(
                left: 1458,
                top: 236,
                width: 250,
                height: 250,
                child: PlaceStampView(placeType: p.placeType, imageUrl: p.stampImageUrl, size: 250),
              ),
              Positioned(
                left: 1412,
                width: 342,
                top: _topFor(532, 34),
                child: text(BoardingPassLabels.seat, _rubik(34, FontWeight.w400, _labelColor, letterSpacing: 3.4),
                    align: TextAlign.center),
              ),
              Positioned(
                left: 1412,
                width: 342,
                top: _topFor(575, 67),
                child: text(p.seat, _rubik(67, FontWeight.w700, _valueColor),
                    key: const ValueKey('boardingPassSeat'), align: TextAlign.center),
              ),
              if (watermarked)
                Positioned.fill(
                  child: ClipPath(
                    clipper: const _TicketShapeClipper(),
                    child: Stack(children: [
                      CardWatermark(
                          key: const ValueKey('boardingPassWatermark'),
                          canvas: kBoardingPassCanvas,
                          opacity: kKeepsakeWatermarkOpacity),
                    ]),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// 邮票框里的场所照：场所首图 → 包内默认场所图 → 代码占位（类型图标 + 浅紫底）。
  Widget _photo(BoardingPassDetail p) {
    final placeholder = Container(
      key: const ValueKey('boardingPassImagePlaceholder'),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFD9C8E6), Color(0xFFB79CCB)],
        ),
      ),
      alignment: Alignment.center,
      child: Icon(PlaceStampPlaceholder.iconFor(p.placeType), size: 150, color: Colors.white),
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

/// 票面外形（画布坐标）：左侧圆角 50、票根撕线上下各一个半圆缺口（圆心 x=1412，r=47）、
/// 右侧两角内凹（圆心在角上，r=45）。水印按它裁，缺口与圆角外保持透明。
class _TicketShapeClipper extends CustomClipper<Path> {
  const _TicketShapeClipper();

  @override
  Path getClip(Size size) {
    final sx = size.width / kBoardingPassCanvas.width;
    final sy = size.height / kBoardingPassCanvas.height;
    final body = Path()
      ..addRRect(RRect.fromRectAndCorners(
        Rect.fromLTWH(0, 0, size.width, size.height),
        topLeft: Radius.elliptical(50 * sx, 50 * sy),
        bottomLeft: Radius.elliptical(50 * sx, 50 * sy),
      ));
    final cuts = Path()
      ..addOval(Rect.fromCenter(center: Offset(1412 * sx, 0), width: 94 * sx, height: 94 * sy))
      ..addOval(Rect.fromCenter(center: Offset(1412 * sx, size.height), width: 94 * sx, height: 94 * sy))
      ..addOval(Rect.fromCenter(center: Offset(size.width, 0), width: 90 * sx, height: 90 * sy))
      ..addOval(Rect.fromCenter(center: Offset(size.width, size.height), width: 90 * sx, height: 90 * sy));
    return Path.combine(PathOperation.difference, body, cuts);
  }

  @override
  bool shouldReclip(covariant CustomClipper<Path> oldClipper) => false;
}
