import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../core/theme/colors.dart';
import '../../domain/milestone.dart';
import '../../domain/milestone_badge_assets.dart';

/// 徽章素材是否存在（V1.3.2 Story 5.1 · AC2.3）：从 asset manifest 读一次、缓存。
///
/// 未读完前一律按「不存在」渲染回落（不闪白、不出占位图）；读完后已挂载的徽章自动刷新。
class MilestoneBadgeAssets {
  MilestoneBadgeAssets._();

  /// 测试缝：非 null 时直接以它为「已入包的素材路径集合」。
  @visibleForTesting
  static Set<String>? debugOverride;

  static final ValueNotifier<Set<String>?> _loaded = ValueNotifier<Set<String>?>(null);
  static Future<void>? _loading;

  /// 已知的素材集合；null = 还没读完。
  static Set<String>? get current => debugOverride ?? _loaded.value;

  static ValueListenable<Set<String>?> get listenable => _loaded;

  /// 触发一次读取（幂等）。读失败按「全都不存在」处理 —— 永远回落，不影响页面。
  static void ensureLoaded() {
    if (debugOverride != null || _loaded.value != null || _loading != null) return;
    _loading = () async {
      try {
        final manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
        _loaded.value = manifest.listAssets().toSet();
      } catch (_) {
        _loaded.value = const <String>{};
      }
    }();
  }

  static bool has(String path) => current?.contains(path) ?? false;

  /// 该 code 的（未锁定）徽章素材是否已入包。给需要按「有没有图」切换整块排版的调用方用（如 Diary 角标）。
  static bool hasArtFor(String code) {
    final key = milestoneBadgeKeyOf(code);
    return key != null && has(milestoneBadgeAssetPath(key));
  }
}

/// 里程碑徽章公共组件（V1.3.2 Story 5.1）：六处（庆祝页大徽章 / KOLEKSI / 列表墙 / 底抽屉 /
/// Diary banner 与角标 / 通知中心）**只传 `size`**，「取哪张图、有没有图」全部在这里判断。
///
/// - 未锁定且该 code 的语义键素材已入包 → 该枚素材（同一张图等比缩放）；
/// - [locked] → 全局锁定图（存在时）；🔴 **永不读该枚真图做灰度**（锁定态不能暴露是哪一枚）；
///   锁定图也不存在 → 浅灰圆 + 锁；
/// - 素材不存在 → 调用方的 [fallback]；没给则「奖杯 + 级别色圆底」。
class MilestoneBadge extends StatefulWidget {
  const MilestoneBadge({
    super.key,
    required this.code,
    required this.size,
    this.locked = false,
    this.level,
    this.fallback,
    this.lockedFallback,
  });

  final String code;
  final double size;
  final bool locked;
  final MilestoneLevel? level;

  /// 无素材时的回落外观（让素材一枚未到时各处「零视觉变化」）。
  final WidgetBuilder? fallback;

  /// 锁定且无全局锁定图时的回落外观；没给则「浅灰圆 + 锁」。
  final WidgetBuilder? lockedFallback;

  @override
  State<MilestoneBadge> createState() => _MilestoneBadgeState();
}

class _MilestoneBadgeState extends State<MilestoneBadge> {
  @override
  void initState() {
    super.initState();
    MilestoneBadgeAssets.ensureLoaded();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Set<String>?>(
      valueListenable: MilestoneBadgeAssets.listenable,
      builder: (context, _, _) => _resolve(context),
    );
  }

  Widget _resolve(BuildContext context) {
    if (widget.locked) {
      final path = milestoneBadgeAssetPath(kMilestoneBadgeLockedKey);
      if (MilestoneBadgeAssets.has(path)) return _image(path, _lockedFallback);
      return _lockedFallback(context);
    }
    final key = milestoneBadgeKeyOf(widget.code);
    if (key != null) {
      final path = milestoneBadgeAssetPath(key);
      if (MilestoneBadgeAssets.has(path)) return _image(path, _fallback);
    }
    return _fallback(context);
  }

  Widget _image(String path, WidgetBuilder onError) => Image.asset(
        path,
        key: ValueKey('milestoneBadgeArt_${widget.code}'),
        width: widget.size,
        height: widget.size,
        fit: BoxFit.contain,
        filterQuality: FilterQuality.medium,
        // 双保险：manifest 里有但解码失败时同样回落。
        errorBuilder: (context, _, _) => onError(context),
      );

  Widget _fallback(BuildContext context) {
    final custom = widget.fallback;
    if (custom != null) return custom(context);
    final color = switch (widget.level) {
      MilestoneLevel.l => AppColors.gold,
      MilestoneLevel.s => AppColors.triageGreen,
      MilestoneLevel.m || null => AppColors.mint,
    };
    return Container(
      width: widget.size,
      height: widget.size,
      alignment: Alignment.center,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      child: Icon(Icons.emoji_events_rounded, color: Colors.white, size: widget.size * 0.4),
    );
  }

  Widget _lockedFallback(BuildContext context) => widget.lockedFallback?.call(context) ?? Container(
        width: widget.size,
        height: widget.size,
        alignment: Alignment.center,
        decoration: const BoxDecoration(color: AppColors.line2, shape: BoxShape.circle),
        child: Icon(Icons.lock_outline_rounded, color: AppColors.muted, size: widget.size * 0.4),
      );
}
