import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/milestone.dart';
import '../../domain/milestone_celebration_copy.dart';

/// 里程碑三级庆祝动效（Story 8.5 · FR-42）。完成后按级触发，mint 风格、无第三方动画包（手绘 implicit 动画）：
/// - **S（小）**：半屏庆祝弹层，1-2 秒自动消失，含徽章展示。
/// - **M（中）**：全屏动效约 3 秒 + 徽章解锁（锁→奖杯 burst）。
/// - **L（大）**：Duolingo 开宝箱式交互（宝箱→爆发→奖杯），结束自动衔接分享卡（8.6 通过 [onShare] 注入）。
///
/// 云端 headless 验不了视觉/计时观感（L2 待本地）；本组件保证构建/计时/自动消失逻辑 L0 可测。
/// 庆祝振动通道：原生直接驱动 `Vibrator`，绕过系统「触感反馈」开关（见 android MainActivity.kt）。
const MethodChannel _hapticsChannel = MethodChannel('petgo/haptics');

/// 触发一次短振动；无原生实现（iOS 等）回退系统 HapticFeedback。
Future<void> _celebrationVibrate() async {
  try {
    await _hapticsChannel.invokeMethod<void>('vibrate', {'ms': 45});
  } catch (_) {
    try {
      await HapticFeedback.vibrate();
    } catch (_) {}
  }
}

Future<void> showMilestoneCelebration(
  BuildContext context,
  MilestoneItem item, {
  required String petName,
  FutureOr<void> Function()? onShare,
  VoidCallback? onSeeAll,
  List<MilestoneItem> collection = const [],
}) {
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'milestone-celebration',
    barrierColor: Colors.black.withValues(alpha: 0.6),
    transitionDuration: const Duration(milliseconds: 240),
    pageBuilder: (_, _, _) => _MilestoneCelebrationView(
        item: item, petName: petName, onShare: onShare, onSeeAll: onSeeAll, collection: collection),
  );
}

class _MilestoneCelebrationView extends StatefulWidget {
  const _MilestoneCelebrationView(
      {required this.item,
      required this.petName,
      this.onShare,
      this.onSeeAll,
      this.collection = const []});

  final MilestoneItem item;
  final String petName;
  final FutureOr<void> Function()? onShare;
  final VoidCallback? onSeeAll;

  /// 全部里程碑（用于「已解锁合集」预览）；为空则不显示该区。
  final List<MilestoneItem> collection;

  @override
  State<_MilestoneCelebrationView> createState() => _MilestoneCelebrationViewState();
}

class _MilestoneCelebrationViewState extends State<_MilestoneCelebrationView>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    )..forward();
    // 解锁瞬间一次振动反馈（弹框一打开即触发，方便测）。
    _celebrationVibrate();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _close() {
    if (mounted && Navigator.of(context).canPop()) {
      Navigator.of(context).pop();
    }
  }

  Future<void> _onSharePressed() async {
    if (widget.onShare != null) {
      await widget.onShare!.call();
    }
    _close();
  }

  // 查看全部里程碑：先关庆祝层，再跳里程碑列表（由调用方注入跳转）。
  void _seeAll() {
    _close();
    widget.onSeeAll?.call();
  }

  @override
  Widget build(BuildContext context) => _celebration(context);

  // ---- P-35 统一解锁庆祝（不分级别，原型 milestone-unlock）：纯深色全屏页 + 渐变大徽章
  //      + 解锁标头 + 标题/正文 + 宠物·日期 + 分享到社区 + 查看全部 ----
  Widget _celebration(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final copy = localizedMilestoneCelebration(widget.item.code, locale, widget.petName);
    final dateText = widget.item.completedAt == null
        ? widget.petName
        : '${widget.petName} · ${_formatLongDate(widget.item.completedAt!, locale)}';
    // 整页纯深色（#141019），非半透明遮罩——与原型一致。
    return Material(
      color: AppColors.splashInk,
      child: Stack(
        children: [
          // 掉落彩纸（纯自绘，不挡交互）。
          const Positioned.fill(child: IgnorePointer(child: _Confetti())),
          SafeArea(
            child: Center(
              key: const ValueKey('milestoneCelebration'),
              child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ScaleTransition(
                  scale: CurvedAnimation(parent: _controller, curve: Curves.elasticOut),
                  child: SizedBox(
                    width: 140,
                    height: 120,
                    child: Stack(
                      alignment: Alignment.center,
                      clipBehavior: Clip.none,
                      children: [
                        _badge(120),
                        // 级别小标签，叠在徽章下沿（原型 milestone-unlock）。
                        Positioned(bottom: -10, child: _levelChip(l10n)),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 26),
                // 解锁标头（大写、字距）。
                Text(l10n.milestoneCelebrateUnlocked.toUpperCase(),
                    textAlign: TextAlign.center,
                    style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.5),
                        fontWeight: FontWeight.w700,
                        fontSize: 13,
                        letterSpacing: 0.6)),
                const SizedBox(height: 8),
                // 庆祝标题（粗体，emoji 结尾）。
                Text(copy.title,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        color: Colors.white, fontWeight: FontWeight.w700, fontSize: 26, height: 1.2)),
                if (copy.body.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text(copy.body,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.6), fontSize: 13, height: 1.6)),
                ],
                const SizedBox(height: 20),
                // 宠物 + 日期胶囊。
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.07),
                    borderRadius: BorderRadius.circular(9999),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_speciesEmoji(widget.item.code), style: const TextStyle(fontSize: 16)),
                      const SizedBox(width: 8),
                      Text(dateText,
                          style: TextStyle(
                              fontSize: 12, color: Colors.white.withValues(alpha: 0.7))),
                    ],
                  ),
                ),
                if (widget.collection.isNotEmpty) ...[
                  const SizedBox(height: 24),
                  _collection(l10n),
                ],
                const SizedBox(height: 28),
                if (widget.onShare != null)
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      key: const ValueKey('milestoneShare'),
                      onPressed: _onSharePressed,
                      style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(52),
                        textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                      ),
                      child: Text(l10n.milestoneCelebrateShare),
                    ),
                  ),
                const SizedBox(height: 14),
                // 查看全部里程碑 → 跳转列表页。
                GestureDetector(
                  key: const ValueKey('milestoneCelebrateSeeAll'),
                  onTap: _seeAll,
                  behavior: HitTestBehavior.opaque,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text(l10n.milestoneCelebrateSeeAll,
                        style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.45), fontSize: 13)),
                  ),
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

  // 物种 emoji（按 code 前缀：C=猫 / D=狗 / 其余=通用）。
  String _speciesEmoji(String code) => switch (code.isEmpty ? '' : code[0]) {
        'C' => '🐱',
        'D' => '🐶',
        _ => '🐾',
      };

  // 「15 Juni 2026」/「15 June 2026」——手写月份名，避免依赖 intl locale 数据加载。
  static const _monthsId = [
    'Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni',
    'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'
  ];
  static const _monthsEn = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'
  ];
  String _formatLongDate(DateTime d, Locale locale) {
    final local = d.toLocal();
    final months = locale.languageCode == 'id' ? _monthsId : _monthsEn;
    return '${local.day} ${months[local.month - 1]} ${local.year}';
  }

  // 渐变大徽章（原型 P-35：紫渐变圆 + 辉光 + 白奖杯）。
  Widget _badge(double size) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.mint, AppColors.mint500],
          ),
          boxShadow: [
            BoxShadow(
                color: AppColors.mint.withValues(alpha: 0.45),
                blurRadius: size * 0.22,
                offset: Offset(0, size * 0.06)),
          ],
        ),
        child: Icon(Icons.emoji_events_rounded, color: Colors.white, size: size * 0.5),
      );

  // 级别配色（与列表页一致：L 金 / M 紫 / S 绿）。
  Color _levelColor(MilestoneLevel level) => switch (level) {
        MilestoneLevel.l => AppColors.gold,
        MilestoneLevel.m => AppColors.mint,
        MilestoneLevel.s => AppColors.triageGreen,
      };

  // 级别小标签（M · MAJOR 等），叠在徽章下沿。
  Widget _levelChip(AppLocalizations l10n) {
    final label = switch (widget.item.level) {
      MilestoneLevel.l => l10n.milestoneLevelChipL,
      MilestoneLevel.m => l10n.milestoneLevelChipM,
      MilestoneLevel.s => l10n.milestoneLevelChipS,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
      decoration: BoxDecoration(
        color: _levelColor(widget.item.level),
        borderRadius: BorderRadius.circular(9999),
        border: Border.all(color: AppColors.splashInk, width: 2),
      ),
      child: Text(label,
          style: const TextStyle(
              fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white, letterSpacing: 0.6)),
    );
  }

  // 已解锁徽章合集预览（原型 KOLEKSI）：只展示已解锁项（级别色圆），最多两排，
  // 放不下时最后一格折叠为「+N」，故「圆点数 + N = 已解锁总数」，与列表页进度一致。
  Widget _collection(AppLocalizations l10n) {
    final unlocked = widget.collection.where((m) => m.completed).toList();
    if (unlocked.isEmpty) return const SizedBox.shrink();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Text('${l10n.milestoneCelebrateCollection(widget.petName).toUpperCase()} · ${unlocked.length}',
              textAlign: TextAlign.center,
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.5,
                  color: Colors.white.withValues(alpha: 0.4))),
          const SizedBox(height: 10),
          LayoutBuilder(
            builder: (context, constraints) {
              const cell = 44.0, gap = 8.0;
              // 按实际宽度算每排个数 → 两排容量；超出则末格显示「+N」。
              final perRow = ((constraints.maxWidth + gap) / (cell + gap)).floor().clamp(1, 99);
              final capacity = perRow * 2;
              final List<Widget> cells;
              if (unlocked.length <= capacity) {
                cells = [for (final m in unlocked) _collectionCircle(color: _levelColor(m.level))];
              } else {
                final showN = capacity - 1; // 留一格给「+N」
                cells = [
                  for (final m in unlocked.take(showN)) _collectionCircle(color: _levelColor(m.level)),
                  _collectionCircle(text: '+${unlocked.length - showN}'),
                ];
              }
              return Wrap(
                spacing: gap,
                runSpacing: gap,
                alignment: WrapAlignment.center,
                children: cells,
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _collectionCircle({Color? color, String? text}) => Container(
        width: 44,
        height: 44,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: color == null
              ? null
              : LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [color, Color.lerp(color, Colors.white, 0.25)!]),
          color: color == null ? Colors.white.withValues(alpha: 0.1) : null,
          boxShadow: color == null
              ? null
              : [BoxShadow(color: color.withValues(alpha: 0.4), blurRadius: 8, offset: const Offset(0, 3))],
        ),
        child: text != null
            ? Text(text,
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white.withValues(alpha: 0.6)))
            : const Icon(Icons.emoji_events_rounded, color: Colors.white, size: 20),
      );
}

/// 持续掉落彩纸覆盖层（纯自绘 CustomPainter，无第三方包）。
///
/// 连续不断的关键：每片彩纸有独立的初始相位 [offset] 均匀铺在 0..1（即任意时刻整屏都有彩纸），
/// 且每轮的「下落圈数 [fallTurns]」「自转圈数 [spinTurns]」「左右摆动圈数 [swayTurns]」都取**整数**，
/// 使位置/角度对 progress 以 1 为周期连续 —— controller `repeat()` 在 1→0 回绕时无跳变、无空档。
class _Confetti extends StatefulWidget {
  const _Confetti();

  @override
  State<_Confetti> createState() => _ConfettiState();
}

class _ConfettiState extends State<_Confetti> with SingleTickerProviderStateMixin {
  late final AnimationController _c;
  late final List<_ConfettiPiece> _pieces;

  static const _palette = [
    AppColors.mint,
    AppColors.gold,
    AppColors.coral,
    AppColors.triageGreen,
    AppColors.mint500,
    AppColors.grape,
  ];

  @override
  void initState() {
    super.initState();
    final rnd = Random();
    _pieces = List.generate(54, (i) {
      return _ConfettiPiece(
        x: rnd.nextDouble(),
        size: 5 + rnd.nextDouble() * 9,
        color: _palette[i % _palette.length],
        offset: rnd.nextDouble(), // 均匀铺满整屏，无空档
        fallTurns: 1 + rnd.nextInt(2), // 每轮下落 1~2 屏（整数 → 回绕连续）
        spinTurns: (rnd.nextBool() ? 1 : -1) * (1 + rnd.nextInt(2)), // 整数圈自转
        swayTurns: 1 + rnd.nextInt(2), // 整数次左右摆动
        swayAmp: 10 + rnd.nextDouble() * 34,
        swayPhase: rnd.nextDouble() * pi * 2,
        spin0: rnd.nextDouble() * pi * 2,
      );
    });
    _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 5200))..repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (_, _) => CustomPaint(
          size: Size.infinite,
          painter: _ConfettiPainter(_c.value, _pieces),
        ),
      );
}

class _ConfettiPiece {
  const _ConfettiPiece({
    required this.x,
    required this.size,
    required this.color,
    required this.offset,
    required this.fallTurns,
    required this.spinTurns,
    required this.swayTurns,
    required this.swayAmp,
    required this.swayPhase,
    required this.spin0,
  });

  final double x; // 0..1 水平基准
  final double size;
  final Color color;
  final double offset; // 0..1 垂直初始相位
  final int fallTurns; // 每轮下落屏数（整数）
  final int spinTurns; // 每轮自转圈数（整数，带方向）
  final int swayTurns; // 每轮摆动次数（整数）
  final double swayAmp; // 水平摆幅 px
  final double swayPhase;
  final double spin0; // 初始角度
}

class _ConfettiPainter extends CustomPainter {
  _ConfettiPainter(this.progress, this.pieces);

  final double progress;
  final List<_ConfettiPiece> pieces;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint();
    for (final p in pieces) {
      // 垂直相位 0..1（整数 fallTurns → 1→0 回绕处连续）。
      final phase = (p.offset + progress * p.fallTurns) % 1.0;
      final y = phase * (size.height + 40) - 20;
      final x = p.x * size.width + sin(phase * 2 * pi * p.swayTurns + p.swayPhase) * p.swayAmp;
      final rot = p.spin0 + progress * p.spinTurns * 2 * pi;
      paint.color = p.color;
      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(rot);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(center: Offset.zero, width: p.size, height: p.size * 0.6),
          const Radius.circular(2),
        ),
        paint,
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _ConfettiPainter oldDelegate) => oldDelegate.progress != progress;
}
