import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_painted_image.dart';
import '../../../../shared/card_render/card_render_pipeline.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/celebration_pet.dart';
import '../../domain/milestone.dart';
import '../../domain/milestone_badge_assets.dart';
import '../../domain/milestone_compose_preset.dart';
import '../../domain/milestone_titles.dart';
import 'milestone_badge.dart';
import 'pet_species_avatar.dart';

/// 里程碑卡用的画布：1:1（1080×1080，r48）。Feed 瀑布流里显示更完整（PRD §3.6 里程碑卡图）。
const CardCanvas kMilestoneShareCanvas = CardCanvas.square;

/// 字标素材（纯白，卡面再上白色 srcIn 双保险）。
const String kMilestoneShareWordmark = 'assets/brand/wordmark_brand.svg';

/// 花结正式 SVG（**待素材**，路径见 [milestoneRosetteAssetPath]）：按级别三色三份（L 金 / M 紫 / S 绿），
/// 设计交付后同名放入即自动切换；未入包 → 简化同心圆兜底（UI D6 说明：稿中花结是示意绘制，不照稿手写定稿）。
String _rosettePath(MilestoneLevel level) => milestoneRosetteAssetPath(level.name);

/// 级别色：只出现在花结环、飘带、芯片（同 `milestone_celebration.dart` `_levelColor`）。
Color milestoneShareLevelColor(MilestoneLevel level) => switch (level) {
      MilestoneLevel.l => AppColors.gold,
      MilestoneLevel.m => AppColors.mint,
      MilestoneLevel.s => AppColors.triageGreen,
    };

/// 飘带色：M 级紫飘带在紫底上看不清 → 深一档 `mint600` + 12px 白描边（2026-11-03 拍板）；L / S 同级别色、无描边。
Color milestoneShareRibbonColor(MilestoneLevel level) =>
    level == MilestoneLevel.m ? AppColors.mint600 : milestoneShareLevelColor(level);

double milestoneShareRibbonStroke(MilestoneLevel level) => level == MilestoneLevel.m ? 12 : 0;

/// 里程碑卡（V1.4.2 social-topic Story 3.1 · FR-156 · UI D6 / D7 · 架构 D-21）。
///
/// **按 1080 画布坐标排版**，由调用方包进 `CardFrame(canvas: kMilestoneShareCanvas)`（`FittedBox` 缩放显示，
/// `CardRenderPipeline.capture` 按画布宽反算倍率导出）。卡面五项：奖章花结 + 级别芯片 + 短标题 +
/// 宠物头像与名字 · 完成日期 + TailTopia 字标。🔴 **无二维码、无水印**（站内帖子不需要下载引导；里程碑不是付费内容）。
///
/// 宠物 1~5 只只改白色页脚，上半部（字标 / 花结 / 标题）不随宠物数变。
/// 截图前须 [captureMilestoneShareCard]（等图片真正画上屏，否则会截出白图）。
///
/// 屏外挂载（供 Story 3.2 庆祝层用；**不要**用 `Offstage` / `Visibility` / `Opacity(0)`，它们不绘制，截出来是空图）：
/// ```dart
/// Positioned(left: -2000, top: 0, width: 360,
///   child: IgnorePointer(child: CardFrame(boundaryKey: k, canvas: kMilestoneShareCanvas,
///     child: MilestoneShareCard(item: item, pets: pets, completedAt: at))))
/// ```
class MilestoneShareCard extends StatelessWidget {
  const MilestoneShareCard({super.key, required this.item, required this.pets, required this.completedAt});

  final MilestoneItem item;

  /// 页脚宠物：单宠里程碑 = 这只；账号级 = 全部 ACTIVE（建档顺序）。最多显示 5 只。
  final List<CelebrationPet> pets;
  final DateTime completedAt;

  static const double _pad = 72;
  static const double _footerHeight = 208;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final level = item.level;
    final chip = switch (level) {
      MilestoneLevel.l => l10n.milestoneLevelChipL,
      MilestoneLevel.m => l10n.milestoneLevelChipM,
      MilestoneLevel.s => l10n.milestoneLevelChipS,
    };
    return DefaultTextStyle(
      style: const TextStyle(fontFamily: 'Poppins', color: Colors.white, decoration: TextDecoration.none),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(kMilestoneShareCanvas.radius),
        child: DecoratedBox(
          key: const ValueKey('milestoneShareCard'),
          // CSS 155° 渐变（mint500 → mint 46% → mint600）。
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment(-0.4226, -0.9063),
              end: Alignment(0.4226, 0.9063),
              colors: [AppColors.mint500, AppColors.mint, AppColors.mint600],
              stops: [0, 0.46, 1],
            ),
          ),
          child: Stack(
            children: [
              Positioned(
                left: _pad,
                top: 76,
                width: 200,
                height: 62,
                child: SvgPicture.asset(
                  kMilestoneShareWordmark,
                  key: const ValueKey('milestoneShareWordmark'),
                  width: 200,
                  colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
                ),
              ),
              Positioned(
                left: _pad,
                top: 222,
                width: 520,
                child: Text(
                  l10n.milestoneCelebrateUnlocked.toUpperCase(),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 28,
                      height: 1.2,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 3,
                      color: Colors.white.withValues(alpha: 0.72)),
                ),
              ),
              Positioned(
                left: _pad,
                top: 276,
                child: Container(
                  key: const ValueKey('milestoneShareLevelChip'),
                  padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 8),
                  decoration: BoxDecoration(
                    color: milestoneShareLevelColor(level),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(chip,
                      style: const TextStyle(
                          fontSize: 28, height: 1.2, fontWeight: FontWeight.w700, letterSpacing: 2)),
                ),
              ),
              Positioned(
                right: 40,
                top: 28,
                width: 440,
                height: 620,
                child: _MilestoneRosette(code: item.code, level: level),
              ),
              Positioned(
                left: _pad,
                right: _pad,
                bottom: _footerHeight + 52,
                child: Text(
                  localizedMilestoneTitle(item.code, locale),
                  key: const ValueKey('milestoneShareTitle'),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.left,
                  style: const TextStyle(
                      fontSize: 92, height: 1.04, fontWeight: FontWeight.w800, letterSpacing: -2),
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                height: _footerHeight,
                child: _Footer(pets: pets.take(5).toList(), date: formatMilestoneShortDate(completedAt, locale)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 奖章花结：正式 SVG 到货 → SVG + 徽章；未到 → 简化同心圆（白外圆 + 级别色环 16 + 白环 + 徽章 276）+ 两条飘带。
///
/// 盒子 440×620（画布坐标，右 40 上 28）；徽章 276 位于盒内 (82, 82)，即画布 (682, 110)。
class _MilestoneRosette extends StatelessWidget {
  const _MilestoneRosette({required this.code, required this.level});

  final String code;
  final MilestoneLevel level;

  static const double _badge = 276;
  static const double _cx = 220;
  static const double _cy = 220;

  @override
  Widget build(BuildContext context) {
    MilestoneBadgeAssets.ensureLoaded();
    return ValueListenableBuilder<Set<String>?>(
      valueListenable: MilestoneBadgeAssets.listenable,
      builder: (context, _, _) {
        final loaded = MilestoneBadgeAssets.current != null;
        final rosettePath = _rosettePath(level);
        final hasRosette = MilestoneBadgeAssets.has(rosettePath);
        return Stack(
          key: const ValueKey('milestoneShareRosette'),
          clipBehavior: Clip.none,
          children: [
            if (hasRosette)
              Positioned.fill(
                child: SvgPicture.asset(rosettePath,
                    key: const ValueKey('milestoneShareRosetteArt'), fit: BoxFit.contain),
              )
            else ...[
              _ribbon(left: true),
              _ribbon(left: false),
              _circle(380, Colors.white, const ValueKey('milestoneShareRosetteFallback')),
              _circle(332, milestoneShareLevelColor(level), const ValueKey('milestoneShareRosetteRing')),
              _circle(300, Colors.white, null),
            ],
            Positioned(
              left: _cx - _badge / 2,
              top: _cy - _badge / 2,
              width: _badge,
              height: _badge,
              child: _badgeArt(loaded),
            ),
          ],
        );
      },
    );
  }

  Widget _circle(double d, Color color, Key? key) => Positioned(
        left: _cx - d / 2,
        top: _cy - d / 2,
        width: d,
        height: d,
        child: DecoratedBox(key: key, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
      );

  // 飘带（兜底版：两条圆角长条，左右各斜 12°，压在白外圆下）。M 级 mint600 + 12 白描边。
  Widget _ribbon({required bool left}) {
    final stroke = milestoneShareRibbonStroke(level);
    return Positioned(
      left: left ? 92 : 238,
      top: 330,
      width: 110,
      height: 270,
      child: Transform.rotate(
        angle: (left ? 12 : -12) * 3.1415926 / 180,
        child: Container(
          key: ValueKey(left ? 'milestoneShareRibbonL' : 'milestoneShareRibbonR'),
          decoration: BoxDecoration(
            color: milestoneShareRibbonColor(level),
            borderRadius: BorderRadius.circular(18),
            border: stroke > 0 ? Border.all(color: Colors.white, width: stroke) : null,
          ),
        ),
      ),
    );
  }

  /// 徽章：素材入包 → 该枚（可等首帧）；未入包 → 紫渐变奖杯回落（与庆祝页一致）。
  /// 素材清单未读完 → 先画回落但**不算就绪**（读完后换成真节点，截图方会重新等）。
  Widget _badgeArt(bool loaded) {
    final key = milestoneBadgeKeyOf(code);
    final path = key == null ? null : milestoneBadgeAssetPath(key);
    final hasArt = path != null && MilestoneBadgeAssets.has(path);
    return CardPaintedImage(
      key: ValueKey('milestoneShareBadge_${hasArt ? 'art' : 'fallback'}_$loaded'),
      image: hasArt ? AssetImage(path) : null,
      pending: !loaded,
      width: _badge,
      height: _badge,
      fit: BoxFit.contain,
      fallback: (_) => const _TrophyFallback(size: _badge),
    );
  }
}

/// 紫渐变奖杯（庆祝页 `_badge` 的同款回落）。
class _TrophyFallback extends StatelessWidget {
  const _TrophyFallback({required this.size});

  final double size;

  @override
  Widget build(BuildContext context) => Container(
        key: const ValueKey('milestoneShareBadgeFallback'),
        width: size,
        height: size,
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.mint, AppColors.mint500],
          ),
        ),
        child: Icon(Icons.emoji_events_rounded, color: Colors.white, size: size * 0.5),
      );
}

/// 白色页脚（UI D7）：1 只 = 头像 112 + 名字 40；2–5 只 = 头像 88 叠放（每只向左 −22，白 6 描边）+ 名字一句
/// （「A & B」40px / 「A, B & C」34px，最多 2 行省略，不用 `dkk.`）；日期 28 muted。
class _Footer extends StatelessWidget {
  const _Footer({required this.pets, required this.date});

  final List<CelebrationPet> pets;
  final String date;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final single = pets.length <= 1;
    final avatar = single ? 112.0 : 88.0;
    final String names;
    if (pets.isEmpty) {
      names = '';
    } else if (pets.length == 1) {
      names = pets.single.name;
    } else {
      final head = pets.sublist(0, pets.length - 1).map((p) => p.name).join(', ');
      names = l10n.diaryPetsPair(head, pets.last.name);
    }
    final nameSize = pets.length >= 3 ? 34.0 : 40.0;
    const step = 88.0 - 22.0;
    return Container(
      key: const ValueKey('milestoneShareFooter'),
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 72),
      child: Row(
        children: [
          if (pets.isNotEmpty) ...[
            SizedBox(
              width: single ? avatar : avatar + step * (pets.length - 1),
              height: avatar,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  for (var i = 0; i < pets.length; i++)
                    Positioned(
                      left: i * step,
                      top: 0,
                      width: avatar,
                      height: avatar,
                      child: _avatar(pets[i], avatar, i, ring: !single),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 28),
          ],
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (names.isNotEmpty) ...[
                  Text(names,
                      key: const ValueKey('milestoneSharePetNames'),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontSize: nameSize, height: 1.2, fontWeight: FontWeight.w700, color: AppColors.ink)),
                  const SizedBox(height: 6),
                ],
                Text(date,
                    key: const ValueKey('milestoneShareDate'),
                    maxLines: 1,
                    style: const TextStyle(fontSize: 28, height: 1.2, color: AppColors.muted)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // 头像走 OSS 缩略图（导出 112px 头像取 240 物理像素足够，不拉原图）；加载失败回落物种默认图（有内容，算已画上屏）。
  Widget _avatar(CelebrationPet p, double size, int i, {required bool ring}) => Container(
        key: ValueKey('milestoneShareAvatar-$i'),
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Colors.white,
          boxShadow: ring ? const [BoxShadow(color: Colors.white, spreadRadius: 6)] : null,
        ),
        child: ClipOval(
          child: CardPaintedImage(
            image: AppImage.provider(p.avatarUrl, thumbWidth: 240),
            width: size,
            height: size,
            fallback: (_) => PetSpeciesAvatar(avatarUrl: null, petType: p.petType, size: size),
          ),
        ),
      );
}

/// 里程碑卡截图（D-21）。
class MilestoneShareCardCapture {
  MilestoneShareCardCapture._();

  /// 测试缝（照 `TailsonalityResultPage.captureForTest`）：非 null 时 [captureMilestoneShareCard] 直接走它。
  @visibleForTesting
  static Future<Uint8List?> Function(GlobalKey boundaryKey)? captureForTest;
}

/// 截 [boundaryKey]（`CardFrame.boundaryKey`）下的里程碑卡为 1080×1080 PNG。
///
/// 先把字标 / 花结 SVG 预加载进缓存，再等徽章 / 头像等图片首帧真正画上屏（[CardPaintedImage.waitPainted]），
/// 最后才截。boundary 未挂载 / 未就绪 / 超时 / SVG 读失败 → `null`（调用方按「出图失败」处理）；**绝不**返回白图。
Future<Uint8List?> captureMilestoneShareCard(GlobalKey boundaryKey,
    {Duration timeout = const Duration(seconds: 8)}) async {
  final seam = MilestoneShareCardCapture.captureForTest;
  if (seam != null) return seam(boundaryKey);
  final ctx = boundaryKey.currentContext;
  if (ctx == null || !ctx.mounted) return null;
  // 整条链路（SVG 预加载 + 等图 + 等帧）共用一个截止时刻：任何一步挂住（如 App 进后台不出帧）都在 [timeout] 内返回 null。
  final deadline = DateTime.now().add(timeout);
  Duration left() => deadline.difference(DateTime.now());
  try {
    await CardPaintedImage.precacheSvgAssets([
      kMilestoneShareWordmark,
      for (final l in MilestoneLevel.values)
        if (MilestoneBadgeAssets.has(_rosettePath(l))) _rosettePath(l),
    ]).timeout(left());
  } catch (_) {
    return null;
  }
  if (!ctx.mounted) return null;
  // SVG 解析好之后，`SvgPicture` 要再 build 一帧才画出来。
  if (!await CardPaintedImage.endOfFrameWithin(left())) return null;
  if (!ctx.mounted) return null;
  if (!await CardPaintedImage.waitPainted(ctx, timeout: left())) return null;
  // 再多等一帧（复审 3-1 PLAUSIBLE-2）：字标 SVG 的 picture 由 `SvgPicture` 异步取回后 setState，
  // 与图片首帧不同步；多一帧代价很低，换导出图里字标一定在。
  if (!await CardPaintedImage.endOfFrameWithin(left())) return null;
  if (boundaryKey.currentContext == null) return null;
  return CardRenderPipeline.capture(boundaryKey: boundaryKey, canvas: kMilestoneShareCanvas);
}
