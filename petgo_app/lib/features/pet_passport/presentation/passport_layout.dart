import 'package:flutter/material.dart';

import '../../../shared/card_render/card_canvas.dart';
import '../../../shared/card_render/card_watermark.dart';
import '../../keepsake/presentation/keepsake_card_style.dart';
import '../../place/domain/place_summary.dart';
import 'default_stamp_assets.dart';

/// 护照本页面版式（2026-10-06 按设计稿 `stamp-place` 重做：B1 空态 / B2 单章 / B2b 纵览三种状态共用）。
///
/// 整本护照是一张固定画布（设计稿原尺寸 828×1587，素材 `assets/passport_book/`），所有坐标、字号按画布写，
/// 外层 [FittedBox] 等比缩放到屏幕可用区。画布分四块：
/// - 页眉：宠物名（左，Rubik Bold）+ 12 位护照号（右，Rubik Regular，等宽数字、不截断）；虚线在底图里；
/// - 内容区 [kPassportBookContentRect]（828×1110）：各状态自己的内容（单章 PageView / 九宫格 / 空态）；
/// - 页脚：左右翻页箭头 + 中间文案（[PassportBookFooter]）；
/// - 底部字标。
const Size kPassportBookSize = Size(828, 1587);

/// 内容区（画布坐标）：页眉虚线之下、页脚之上。
const Rect kPassportBookContentRect = Rect.fromLTWH(0, 150, 828, 1110);

/// 护照本的水印画布（与画布同尺寸、圆角与底图一致；水印层按它裁剪）。
const CardCanvas kPetPassportPageCanvas = CardCanvas(size: kPassportBookSize, radius: 40);

/// 护照本底图路径（58KB WebP，解码要几百毫秒）。
const String kPassportBookBg = 'assets/passport_book/book_bg.webp';

/// 提前把护照本底图解码进图片缓存（2026-10-06 动效验收：不预热时内容先出在白底上，底图约 0.7s 后才「啪」地出现）。
/// 在可能马上进护照本的页面调用（场所详情 → 打卡成功页、Know Your Pet → 宠物足迹）；重复调用无副作用。
void precachePassportBook(BuildContext context) {
  precacheImage(const AssetImage(kPassportBookBg), context, onError: (_, _) {});
  // 7 款按类型的默认章也一起预热：否则章面先闪一下占位图标、约 0.3s 后才换成真章图。
  for (final t in PlaceType.values) {
    final asset = defaultStampAssetFor(t);
    if (asset != null) precacheImage(AssetImage(asset), context, onError: (_, _) {});
  }
}

/// 设计稿字色：正文深棕 / 页脚棕 / 日期浅棕。
abstract final class PassportInk {
  static const Color dark = Color(0xFF603813);
  static const Color footer = Color(0xFF754C24);
  static const Color light = Color(0xFFA67C52);
  static const Color cellBorder = Color(0xFFC69C6D);
  static const Color paper = Color(0xFFF8D8AC);
}

/// 设计稿字体 Rubik（Regular / Medium / Bold，可变字体按 wght 取字重）；行高锁 1.0 便于按坐标摆放。
TextStyle passportRubik(double size, FontWeight weight, Color color, {double height = 1.0}) => TextStyle(
      fontFamily: 'Rubik',
      fontSize: size,
      height: height,
      fontWeight: weight,
      fontVariations: [FontVariation('wght', weight.value.toDouble())],
      color: color,
      decoration: TextDecoration.none,
    );

/// 页面版心：给护照本留出边距并居中（B1 / B2 / B2b / 已购版本回看 / 骨架共用）。
class PassportFrame extends StatelessWidget {
  const PassportFrame({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
        child: Center(child: child),
      ),
    );
  }
}

/// 一整本护照（画布 828×1587 等比缩放）。
///
/// [watermarked]（V1.3.2 Story 3.4）：当前版本未买 → 整本叠品牌水印（裁到护照本圆角内）；已买 / 回看已买版本 → 不叠。
class PassportBook extends StatelessWidget {
  const PassportBook({
    super.key,
    required this.petName,
    required this.passportNo,
    required this.content,
    this.footer,
    this.watermarked = false,
  });

  final String petName;
  final String passportNo;

  /// 内容区（828×1110 画布坐标）。
  final Widget content;

  /// 页脚（画布 y=1259 起、高 127：箭头图标 67 居中于 y=1289..1356，上下留出点击热区）。
  final Widget? footer;
  final bool watermarked;

  @override
  Widget build(BuildContext context) {
    final r = kPassportBookContentRect;
    return AspectRatio(
      key: const ValueKey('passportBook'),
      aspectRatio: kPassportBookSize.width / kPassportBookSize.height,
      child: FittedBox(
        fit: BoxFit.contain,
        child: SizedBox(
          width: kPassportBookSize.width,
          height: kPassportBookSize.height,
          child: Stack(
            children: [
              Positioned.fill(child: Image.asset(kPassportBookBg, fit: BoxFit.fill, gaplessPlayback: true)),
              // ---- 页眉 ----
              Positioned(
                left: 68,
                width: 420,
                top: 73,
                child: Text(petName,
                    maxLines: 1,
                    softWrap: false,
                    overflow: TextOverflow.ellipsis,
                    style: passportRubik(43, FontWeight.w700, PassportInk.dark)),
              ),
              Positioned(
                right: 68,
                top: 82,
                child: Text(passportNo,
                    key: const ValueKey('passportNo'),
                    softWrap: false,
                    overflow: TextOverflow.visible,
                    style: passportRubik(30, FontWeight.w400, PassportInk.dark)
                        .copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
              ),
              // ---- 内容区 ----
              Positioned(left: r.left, top: r.top, width: r.width, height: r.height, child: content),
              // ---- 页脚 ----
              if (footer != null) Positioned(left: 0, right: 0, top: 1259, height: 127, child: footer!),
              // ---- 字标 ----
              Positioned(
                left: 323,
                top: 1469,
                width: 182,
                height: 56,
                child: Image.asset('assets/passport_book/logo.png', fit: BoxFit.contain),
              ),
              if (watermarked)
                Positioned.fill(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(kPetPassportPageCanvas.radius),
                    child: Stack(children: const [
                      // 竖向平铺：护照本竖长，cover 会让平铺图自带的上下空白边落到底部字标那一条（2026-10-06 修）。
                      CardWatermark(
                          key: ValueKey('passportWatermark'),
                          canvas: kPetPassportPageCanvas,
                          opacity: kKeepsakeWatermarkOpacity,
                          tileVertically: true),
                    ]),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 护照本页脚：左右翻页箭头（设计稿 `left/right.png`）+ 居中文案。
///
/// [showArrows] = false 用于 B1 空态（空态无箭头）。不可翻的一侧箭头变淡且不响应。
class PassportBookFooter extends StatelessWidget {
  const PassportBookFooter({
    super.key,
    required this.label,
    this.labelKey,
    this.bold = true,
    this.showArrows = true,
    this.canPrev = false,
    this.canNext = false,
    this.onPrev,
    this.onNext,
  });

  final String label;
  final Key? labelKey;

  /// 「Cap 1/2」「0 Cap」为粗体；「Halaman 1」为常规体（设计稿）。
  final bool bold;
  final bool showArrows;
  final bool canPrev;
  final bool canNext;
  final VoidCallback? onPrev;
  final VoidCallback? onNext;

  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.center,
      children: [
        Text(label,
            key: labelKey,
            style: bold
                ? passportRubik(40, FontWeight.w700, PassportInk.footer)
                : passportRubik(37, FontWeight.w400, PassportInk.footer)),
        if (showArrows) ...[
          Positioned(
              left: 101 - 27,
              top: 3,
              child: _Arrow(
                  key: const ValueKey('passportPrev'),
                  asset: 'assets/passport_book/arrow_left.png',
                  enabled: canPrev,
                  onTap: onPrev)),
          Positioned(
              right: 100 - 27,
              top: 3,
              child: _Arrow(
                  key: const ValueKey('passportNext'),
                  asset: 'assets/passport_book/arrow_right.png',
                  enabled: canNext,
                  onTap: onNext)),
        ],
      ],
    );
  }
}

class _Arrow extends StatelessWidget {
  const _Arrow({super.key, required this.asset, required this.enabled, this.onTap});

  final String asset;
  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: enabled ? onTap : null,
      // 热区 121×121（画布）包住 67 的图标：护照本缩放到手机宽约 0.42 倍时 ≈ 50dp，满足 ≥44dp。
      child: SizedBox(
        width: 121,
        height: 121,
        child: Center(
          child: Opacity(
            opacity: enabled ? 1 : 0.3,
            child: Image.asset(asset, width: 67, height: 67),
          ),
        ),
      ),
    );
  }
}
