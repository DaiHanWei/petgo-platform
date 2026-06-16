import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 启动屏（原型 P-01）。
///
/// 品牌过场：薄荷墨暗底 + 脉冲环 + 爪印图标弹入 + 字标淡入 + 三点 loader + 版本号。
/// ~2.2s 后转 `/home`（再由 router redirect 决定登录/引导/工作台分流）——本屏不做路由门控。
/// 设计源真相：原型主色为紫，但本 app 已确立薄荷绿体系，故辉光/图标用 mint（非原型紫）。
/// 无障碍：系统「减弱动态效果」时降级为静态，仍按时转场（NFR-13）。
class SplashPage extends StatefulWidget {
  const SplashPage({super.key, this.onComplete});

  /// 过场结束回调（默认 `context.go('/home')`；测试可注入以免依赖 GoRouter）。
  final VoidCallback? onComplete;

  /// 品牌过场时长。
  static const Duration hold = Duration(milliseconds: 2200);

  /// 版本号（非翻译项，跟随构建）。
  static const String version = 'v 1.0.0';

  @override
  State<SplashPage> createState() => _SplashPageState();
}

class _SplashPageState extends State<SplashPage> with TickerProviderStateMixin {
  late final AnimationController _enter = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 700),
  );
  late final AnimationController _loop = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2500),
  );
  Timer? _timer;
  bool _started = false;
  bool _reduceMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    _reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (_reduceMotion) {
      _enter.value = 1.0; // 直接到位，不播放
    } else {
      _enter.forward();
      _loop.repeat();
    }
    _timer = Timer(SplashPage.hold, () {
      if (!mounted) return;
      (widget.onComplete ?? () => context.go('/home'))();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _enter.dispose();
    _loop.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final white = AppColors.onAccent;
    return Scaffold(
      backgroundColor: AppColors.splashInk,
      body: Stack(
        children: [
          // 中心辉光（薄荷）
          Center(
            child: Container(
              width: 240,
              height: 240,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [AppColors.splashGlow.withValues(alpha: 0.28), Colors.transparent],
                ),
              ),
            ),
          ),
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // 图标组：脉冲环 + 薄荷渐变圆角方块 + 白爪印
                SizedBox(
                  width: 168,
                  height: 168,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      // 减弱动态时不渲染脉冲环（静态下三层会叠在初始态，无意义）。
                      if (!_reduceMotion)
                        for (final delay in const [0.0, 0.33, 0.66]) _pulseRing(delay),
                      ScaleTransition(
                        scale: CurvedAnimation(parent: _enter, curve: Curves.easeOutBack),
                        child: Container(
                          width: 84,
                          height: 84,
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(26),
                            gradient: const LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [AppColors.mint, AppColors.mint600],
                            ),
                            boxShadow: [
                              BoxShadow(
                                color: AppColors.mint.withValues(alpha: 0.45),
                                blurRadius: 40,
                                spreadRadius: 2,
                              ),
                            ],
                          ),
                          child: Icon(Icons.pets, color: white, size: 44),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 28),
                // 字标 + 副标（淡入）
                FadeTransition(
                  opacity: _enter,
                  child: Column(
                    children: [
                      Text(
                        l10n.appTitle,
                        style: TextStyle(
                          fontSize: 36,
                          fontWeight: FontWeight.w700,
                          color: white,
                          letterSpacing: -1.0,
                          height: 1.0,
                        ),
                      ),
                      const SizedBox(height: 11),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 40),
                        child: Text(
                          l10n.splashTagline,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 12,
                            height: 1.7,
                            color: white.withValues(alpha: 0.45),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          // 三点 loader
          Positioned(
            left: 0,
            right: 0,
            bottom: 110,
            child: Center(child: _DotLoader(controller: _loop)),
          ),
          // 版本号
          Positioned(
            left: 0,
            right: 0,
            bottom: 88,
            child: Text(
              SplashPage.version,
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 10, color: white.withValues(alpha: 0.18), letterSpacing: 0.5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _pulseRing(double delayFraction) {
    return AnimatedBuilder(
      animation: _loop,
      builder: (_, _) {
        final t = (_loop.value + delayFraction) % 1.0;
        return Opacity(
          opacity: (0.6 * (1 - t)).clamp(0.0, 1.0),
          child: Transform.scale(
            scale: 1.0 + t * 1.6,
            child: Container(
              width: 84,
              height: 84,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(26),
                border: Border.all(color: AppColors.splashGlow.withValues(alpha: 0.6), width: 1.5),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// 三点跳动 loader（薄荷渐次）。
class _DotLoader extends StatelessWidget {
  const _DotLoader({required this.controller});

  final AnimationController controller;

  @override
  Widget build(BuildContext context) {
    const colors = [AppColors.mint, AppColors.mint500, AppColors.mintTint];
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < 3; i++) ...[
          if (i > 0) const SizedBox(width: 8),
          AnimatedBuilder(
            animation: controller,
            builder: (_, _) {
              final t = (controller.value + i * 0.18) % 1.0;
              final lift = t < 0.4 ? (t / 0.4) : 0.0;
              return Transform.translate(
                offset: Offset(0, -9 * lift),
                child: Container(
                  width: 6,
                  height: 6,
                  decoration: BoxDecoration(color: colors[i], shape: BoxShape.circle),
                ),
              );
            },
          ),
        ],
      ],
    );
  }
}
