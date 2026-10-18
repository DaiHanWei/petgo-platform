import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../ops/presentation/ops_popup_dialog.dart';
import '../domain/growth_level_color.dart';
import '../domain/growth_models.dart';
import '../domain/growth_popups.dart';
import 'growth_labels.dart';
import 'growth_tier_style.dart';

/// 庆祝 / 汇总弹窗关闭结果：进等级页，或只关掉（✕ / 次按钮 / 返回键 → null 也按 close）。
enum GrowthPopupOutcome { viewLevel, close }

/// 升级庆祝（V1.4.0 ① Story 2.3 · AD-11，UI 稿 D1–D7）。按 `tierUp` 选形态：段位内 → 卡片（D1 / D7），
/// 跨段位 → 浅色全屏（D2 / D3 / D4）。**不复用** `showMilestoneCelebration`（PRD：单独设计）。
///
/// 动效（进度环、彩屑、头像轻弹）全部代码绘制，不引任何动画 / 插画依赖；`disableAnimations` 时首帧即终态。
Future<GrowthPopupOutcome?> showLevelUpCelebration(
  BuildContext rootContext, {
  required GrowthCelebration celebration,
  String? avatarUrl,
  String nickname = '',
  List<GrowthLevelDef> levels = const [],
}) {
  final view = LevelUpCelebrationView(
    celebration: celebration,
    avatarUrl: avatarUrl,
    nickname: nickname,
    levels: levels,
  );
  return showGeneralDialog<GrowthPopupOutcome>(
    context: rootContext,
    useRootNavigator: true,
    barrierDismissible: false,
    barrierLabel: 'growthCelebration',
    // 遮罩同运营弹窗（UI 稿 `.op-ov`）；全屏形态铺白底，遮罩被盖住。
    barrierColor: OpsPopupDialog.barrierColor,
    transitionDuration: const Duration(milliseconds: 200),
    pageBuilder: (context, _, _) => view,
  );
}

/// 卡片 / 全屏的统一入口 widget（便于 widget 测试直接 pump）。
class LevelUpCelebrationView extends StatefulWidget {
  const LevelUpCelebrationView({
    super.key,
    required this.celebration,
    this.avatarUrl,
    this.nickname = '',
    this.levels = const [],
  });

  final GrowthCelebration celebration;
  final String? avatarUrl;
  final String nickname;
  final List<GrowthLevelDef> levels;

  @override
  State<LevelUpCelebrationView> createState() => _LevelUpCelebrationViewState();
}

class _LevelUpCelebrationViewState extends State<LevelUpCelebrationView> with SingleTickerProviderStateMixin {
  late final AnimationController _anim = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1200),
  );
  bool _started = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      Analytics.capture('growth_celebration_shown', {'to_level': widget.celebration.toLevel});
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    final still = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (still) {
      _anim.value = 1; // 首帧即终态
    } else {
      _anim.forward(); // 播一次，不循环
    }
  }

  @override
  void dispose() {
    _anim.dispose();
    super.dispose();
  }

  void _close(GrowthPopupOutcome outcome) => Navigator.of(context).pop(outcome);

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: true,
      child: widget.celebration.tierUp ? _fullScreen(context) : _card(context),
    );
  }

  // ---------------- 段位内：卡片（D1 / D7） ----------------

  Widget _card(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = widget.celebration;
    final tier = c.toTier;
    final jump = c.toLevel - c.fromLevel;
    final isMax = widget.levels.isNotEmpty && c.toLevel >= widget.levels.map((d) => d.level).reduce(math.max);
    final width = math.min(320.0, MediaQuery.sizeOf(context).width - 48);
    return Center(
      child: Material(
        key: const ValueKey('growthCelebrationCard'),
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        child: SizedBox(
          width: width,
          child: Stack(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 28, 20, 16),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AnimatedBuilder(
                      animation: _anim,
                      builder: (context, _) => _RingAvatar(
                        progress: Curves.easeOut.transform((_anim.value / 0.75).clamp(0.0, 1.0)),
                        fromColor: growthLevelColor(c.fromLevel, widget.levels),
                        toColor: growthLevelColor(c.toLevel, widget.levels),
                        level: _anim.value >= 0.75 ? c.toLevel : c.fromLevel,
                        tier: tier,
                        avatarUrl: widget.avatarUrl,
                        nickname: widget.nickname,
                        size: 84,
                      ),
                    ),
                    const SizedBox(height: 14),
                    Text(
                      l10n.growthCelebrationEyebrow,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 0.8,
                        color: GrowthTierStyle.color(tier),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      l10n.growthLevelJump(c.fromLevel, c.toLevel),
                      key: const ValueKey('growthCelebrationJump'),
                      style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: AppColors.ink),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      jump > 1 ? l10n.growthCelebrationCardBodyMulti(jump) : l10n.growthCelebrationCardBody,
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 14, color: AppColors.ink2),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      isMax ? l10n.growthLevelMax : l10n.growthCelebrationKeepGoing(c.toLevel + 1),
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 12, color: AppColors.muted),
                    ),
                    const SizedBox(height: 12),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                      decoration: BoxDecoration(
                        color: GrowthTierStyle.tint(tier),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        l10n.growthExpAmount(growthNumber(context, c.lifetimeTotal)),
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                          fontFeatures: const [FontFeature.tabularFigures()],
                          color: GrowthTierStyle.color(tier),
                        ),
                      ),
                    ),
                    const SizedBox(height: 16),
                    _PrimaryButton(
                      key: const ValueKey('growthCelebrationPrimary'),
                      label: l10n.growthViewMyLevel,
                      onTap: () => _close(GrowthPopupOutcome.viewLevel),
                    ),
                    TextButton(
                      key: const ValueKey('growthCelebrationSecondary'),
                      onPressed: () => _close(GrowthPopupOutcome.close),
                      style: TextButton.styleFrom(minimumSize: const Size(44, 44)),
                      child: Text(l10n.growthCelebrationLater,
                          style: const TextStyle(color: AppColors.ink2, fontWeight: FontWeight.w600)),
                    ),
                  ],
                ),
              ),
              Positioned(top: 4, right: 4, child: _CloseButton(onTap: () => _close(GrowthPopupOutcome.close))),
            ],
          ),
        ),
      ),
    );
  }

  // ---------------- 跨段位：浅色全屏（D2 / D3 / D4） ----------------

  Widget _fullScreen(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = widget.celebration;
    final tier = c.toTier;
    final tierName = GrowthTierStyle.name(l10n, tier);
    final still = MediaQuery.maybeOf(context)?.disableAnimations ?? false;

    final String? description;
    final String primary;
    final String secondary;
    switch (c.frameAction) {
      case GrowthFrameAction.offerNewFrame:
        description = l10n.growthCelebrationOfferFrame;
        primary = l10n.growthUseNewFrame;
        secondary = '${l10n.growthViewMyLevel} →';
      case GrowthFrameAction.auto:
        description = tier == GrowthTier.star
            ? l10n.growthCelebrationStarBody
            : l10n.growthCelebrationAutoFrame(
                tier == GrowthTier.guardian ? l10n.growthFrameAdvanced : l10n.growthFrameBasic);
        primary = l10n.growthViewMyLevel;
        secondary = l10n.commonClose;
      case GrowthFrameAction.none:
        description = null;
        primary = l10n.growthViewMyLevel;
        secondary = l10n.commonClose;
    }
    final benefits = [
      for (final code in c.unlockedBenefits)
        if (GrowthLabels.benefit(l10n, code) case final String text) (code, text),
    ];

    return Material(
      key: const ValueKey('growthCelebrationFull'),
      color: AppColors.surface,
      child: Stack(
        children: [
          // 顶部段位色柔光（径向渐变；色值取段位常量）。
          Positioned(
            top: -120,
            left: 0,
            right: 0,
            height: 420,
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  colors: [GrowthTierStyle.color(tier).withValues(alpha: 0.22), AppColors.surface.withValues(alpha: 0)],
                ),
              ),
            ),
          ),
          // 一次性彩屑（1.2s 停、不循环）。
          if (!still)
            Positioned.fill(
              child: IgnorePointer(
                child: AnimatedBuilder(
                  animation: _anim,
                  builder: (context, _) => CustomPaint(
                    key: const ValueKey('growthConfetti'),
                    painter: _ConfettiPainter(progress: _anim.value, colors: GrowthTierStyle.gradient(tier)),
                  ),
                ),
              ),
            ),
          SafeArea(
            child: Column(
              children: [
                Align(
                  alignment: Alignment.topRight,
                  child: _CloseButton(onTap: () => _close(GrowthPopupOutcome.close)),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Column(
                      children: [
                        _RingAvatar(
                          progress: c.frameAction == GrowthFrameAction.auto ? 1 : 0,
                          fromColor: GrowthTierStyle.color(tier),
                          toColor: GrowthTierStyle.color(tier),
                          level: c.toLevel,
                          tier: tier,
                          avatarUrl: widget.avatarUrl,
                          nickname: widget.nickname,
                          size: 120,
                        ),
                        const SizedBox(height: 16),
                        Text(l10n.growthCelebrationTitle,
                            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink2)),
                        const SizedBox(height: 4),
                        Text(
                          l10n.growthLevelJump(c.fromLevel, c.toLevel),
                          key: const ValueKey('growthCelebrationJump'),
                          style: TextStyle(fontSize: 32, fontWeight: FontWeight.w800, color: GrowthTierStyle.color(tier)),
                        ),
                        const SizedBox(height: 6),
                        Text(l10n.growthCelebrationTierTitle(tierName),
                            textAlign: TextAlign.center,
                            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
                        if (description != null) ...[
                          const SizedBox(height: 6),
                          Text(description,
                              key: const ValueKey('growthCelebrationDescription'),
                              textAlign: TextAlign.center,
                              style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
                        ],
                        if (benefits.isNotEmpty) ...[
                          const SizedBox(height: 20),
                          Container(
                            width: double.infinity,
                            padding: const EdgeInsets.all(16),
                            decoration: BoxDecoration(
                              color: AppColors.surface,
                              borderRadius: BorderRadius.circular(16),
                              border: Border.all(color: AppColors.line2),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(l10n.growthCelebrationUnlockedIn(tierName),
                                    style: const TextStyle(
                                        fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
                                const SizedBox(height: 10),
                                for (final (code, text) in benefits)
                                  Padding(
                                    padding: const EdgeInsets.only(bottom: 8),
                                    child: Row(
                                      children: [
                                        Container(
                                          width: 32,
                                          height: 32,
                                          decoration: BoxDecoration(
                                            color: GrowthTierStyle.tint(tier),
                                            shape: BoxShape.circle,
                                          ),
                                          child: Icon(_benefitIcon(code), size: 16, color: GrowthTierStyle.color(tier)),
                                        ),
                                        const SizedBox(width: 10),
                                        Expanded(
                                          child: Text(text, style: const TextStyle(fontSize: 13, color: AppColors.ink)),
                                        ),
                                      ],
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ],
                        const SizedBox(height: 16),
                      ],
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 8, 24, 12),
                  child: Column(
                    children: [
                      _PrimaryButton(
                        key: const ValueKey('growthCelebrationPrimary'),
                        label: primary,
                        // Story 3.3：改为调 PUT /api/v1/me/growth/frame 一键戴上（D5 成功 / D6 失败）
                        onTap: () => _close(GrowthPopupOutcome.viewLevel),
                      ),
                      TextButton(
                        key: const ValueKey('growthCelebrationSecondary'),
                        onPressed: () => _close(c.frameAction == GrowthFrameAction.offerNewFrame
                            ? GrowthPopupOutcome.viewLevel
                            : GrowthPopupOutcome.close),
                        style: TextButton.styleFrom(minimumSize: const Size(44, 44)),
                        child: Text(secondary,
                            style: const TextStyle(color: AppColors.ink2, fontWeight: FontWeight.w600)),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static IconData _benefitIcon(String code) => switch (code) {
        'PET_SLOT_PLUS_ONE' => Icons.pets,
        'FRAME_BASIC' || 'FRAME_UPGRADED' || 'FRAME_ANIMATED' => Icons.account_circle_outlined,
        'EXCLUSIVE_GIFTS_BATCH1' || 'EXCLUSIVE_GIFTS_BATCH2' => Icons.redeem,
        'FEED_BOOST' => Icons.trending_up,
        _ => Icons.workspace_premium_outlined,
      };
}

/// 品牌主色按钮（同运营弹窗按钮），高 46、热区 ≥ 44。
class _PrimaryButton extends StatelessWidget {
  const _PrimaryButton({super.key, required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: double.infinity,
        height: 46,
        child: Material(
          color: AppColors.mint,
          borderRadius: const BorderRadius.all(Radius.circular(12)),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: Center(
              child: Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700, color: AppColors.onAccent)),
            ),
          ),
        ),
      );
}

class _CloseButton extends StatelessWidget {
  const _CloseButton({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Semantics(
      label: l10n.commonClose,
      button: true,
      excludeSemantics: true,
      onTap: onTap,
      child: GestureDetector(
        key: const ValueKey('growthCelebrationClose'),
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: const SizedBox(
          width: 44,
          height: 44,
          child: Center(child: Icon(Icons.close_rounded, size: 20, color: AppColors.ink2)),
        ),
      ),
    );
  }
}

/// 头像 + 外圈进度环 + 右下段位色数字角标（本地简版；Story 3.2 的共享装饰组件上线后替换）。
class _RingAvatar extends StatelessWidget {
  const _RingAvatar({
    required this.progress,
    required this.fromColor,
    required this.toColor,
    required this.level,
    required this.tier,
    required this.avatarUrl,
    required this.nickname,
    required this.size,
  });

  final double progress;
  final Color fromColor;
  final Color toColor;
  final int level;
  final GrowthTier? tier;
  final String? avatarUrl;
  final String nickname;
  final double size;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ring = Color.lerp(fromColor, toColor, progress) ?? toColor;
    return SizedBox(
      width: size + 12,
      height: size + 12,
      child: Stack(
        alignment: Alignment.center,
        children: [
          CustomPaint(
            size: Size(size + 12, size + 12),
            painter: _RingPainter(progress: progress, color: ring, track: AppColors.line2),
          ),
          InitialAvatar(avatarUrl: avatarUrl, nickname: nickname, radius: size / 2),
          Positioned(
            right: 2,
            bottom: 2,
            child: Container(
              key: const ValueKey('growthCelebrationBadge'),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                gradient: LinearGradient(colors: GrowthTierStyle.gradient(tier)),
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: AppColors.surface, width: 2),
              ),
              child: Text(
                l10n.growthLevelShort('$level'),
                style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                  fontFeatures: [FontFeature.tabularFigures()],
                  color: AppColors.onAccent,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  _RingPainter({required this.progress, required this.color, required this.track});

  final double progress;
  final Color color;
  final Color track;

  @override
  void paint(Canvas canvas, Size size) {
    const stroke = 5.0;
    final rect = Offset.zero & size;
    final arcRect = rect.deflate(stroke / 2);
    canvas.drawArc(arcRect, 0, math.pi * 2, false,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = stroke
          ..color = track);
    if (progress <= 0) return;
    canvas.drawArc(arcRect, -math.pi / 2, math.pi * 2 * progress, false,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = stroke
          ..strokeCap = StrokeCap.round
          ..color = color);
  }

  @override
  bool shouldRepaint(_RingPainter old) => old.progress != progress || old.color != color;
}

/// 一次性彩屑：固定种子的粒子从顶部落下，progress 走完即全部离场（不循环）。
class _ConfettiPainter extends CustomPainter {
  _ConfettiPainter({required this.progress, required this.colors});

  final double progress;
  final List<Color> colors;

  static final List<(double, double, double, int)> _pieces = () {
    final r = math.Random(7);
    return List.generate(36, (i) => (r.nextDouble(), r.nextDouble() * 0.3, 0.6 + r.nextDouble() * 0.6, i));
  }();

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0 || progress >= 1) return;
    final palette = [...colors, AppColors.gold, AppColors.coral];
    for (final (x, delay, speed, i) in _pieces) {
      final t = ((progress - delay) / (1 - delay)).clamp(0.0, 1.0);
      if (t <= 0) continue;
      final dy = -20 + t * speed * size.height;
      final dx = x * size.width + math.sin(t * math.pi * 2 + i) * 12;
      final paint = Paint()..color = palette[i % palette.length].withValues(alpha: 1 - t * 0.6);
      canvas.save();
      canvas.translate(dx, dy);
      canvas.rotate(t * math.pi * 2 * (i.isEven ? 1 : -1));
      canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(-4, -2, 8, 4), const Radius.circular(1)), paint);
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_ConfettiPainter old) => old.progress != progress;
}
