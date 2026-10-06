import 'package:flutter/foundation.dart'
    show Factory, TargetPlatform, ValueListenable, defaultTargetPlatform;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';

/// 详情页的定位小地图（V1.3.0 batch-b1 Story 1.5 · UI 稿 A4 · AD-3 / B1-D9）。
///
/// <h2>🔴 地图允许出现的第二处（也是最后一处）</h2>
/// AD-3 Rule 3：全 App 只有**选点弹层**与**本文件**可以嵌地图。
/// **列表页不得嵌地图** —— 那是 PRD ⑥ 明确排除的「地图浏览视图」。
/// `map_usage_boundary_test.dart` 的白名单里就这两个文件，加第三处会红。
///
/// <h2>🔴 只显示位置，不可浏览</h2>
/// B1-D9 的原话是「只显示位置、不可浏览搜索」。所以这里**关掉全部手势**
/// （`zoomGesturesEnabled` / `scrollGesturesEnabled` / … 全 false）：
/// 它是一张"活的图片"，不是一个可探索的地图。想逛就点右下角跳系统地图 App。
///
/// 这也顺带解决了手势问题：本 widget 在一个可滚动的详情页里，
/// 一张能拖的地图会把页面滚动吃掉。
///
/// <p>🛡 与 PRD ⑥ 不冲突：那条排除的是「列表页在地图上找场所」，本处只是定位展示。
class PlaceMiniMap extends StatefulWidget {
  const PlaceMiniMap({
    super.key,
    required this.latitude,
    required this.longitude,
    required this.name,
    required this.onOpenExternal,
  });

  final double latitude;
  final double longitude;

  /// 仅用于标记的无障碍/信息窗标题 —— **不参与任何地图服务调用**。
  final String name;

  /// 「在地图中打开」：普通跳转链接，不算 API 调用、不计费（AD-3 Rule 5）。
  final VoidCallback onOpenExternal;

  static const double _height = 120;

  @override
  State<PlaceMiniMap> createState() => _PlaceMiniMapState();
}

class _PlaceMiniMapState extends State<PlaceMiniMap> {
  /// 地图画好后截的一张图：返回时顶替原生地图（bug 20260925-572，见 [PlaceMiniMapPopGuard]）。
  MemoryImage? _snapshot;

  void _onMapCreated(GoogleMapController c) {
    // 没有撤图守卫（iOS）就不截 —— 用不上。
    if (context.getInheritedWidgetOfExactType<_RetireScope>() == null) return;
    // 插件没有「瓦片加载完」回调：等一会儿再截。用户在这之前就返回 → 用占位底色，不影响返回。
    Future<void>.delayed(const Duration(milliseconds: 1200), () async {
      if (!mounted) return;
      try {
        final bytes = await c.takeSnapshot();
        if (bytes == null || !mounted) return;
        // 🔴 截到就先解码好：实测返回那一刻才解码，撤图首帧渲染会顶到 100～230ms，
        // 比卡顿本身还重。预解码后返回时只是贴一张现成的图。
        final image = MemoryImage(bytes);
        await precacheImage(image, context);
        _snapshot = image;
      } catch (_) {
        // 截不到就算了，返回时退回占位底色。
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final retired = PlaceMiniMapPopGuard._retiredOf(context);
    if (retired == null) return _frame(context, retired: false);
    return ValueListenableBuilder<bool>(
      valueListenable: retired,
      builder: (context, isRetired, _) => _frame(context, retired: isRetired),
    );
  }

  Widget _frame(BuildContext context, {required bool retired}) {
    final l10n = AppLocalizations.of(context);
    final latitude = widget.latitude;
    final longitude = widget.longitude;
    final onOpenExternal = widget.onOpenExternal;
    final target = LatLng(latitude, longitude);
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: Container(
        height: PlaceMiniMap._height,
        // UI 稿 A4：1px 中性描边画在前景层（地图瓦片之上），圆角与裁剪一致。
        foregroundDecoration: BoxDecoration(
          border: Border.all(color: AppColors.line),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Stack(
          children: [
            // 🔴 撤图（bug 20260925-572）：返回前把原生地图换成截图 / 占位底色，
            // 返回动画里就没有原生控件要合成。
            if (retired)
              Positioned.fill(
                child: _snapshot != null
                    ? Image(image: _snapshot!, fit: BoxFit.cover, gaplessPlayback: true)
                    : const ColoredBox(color: AppColors.cream2),
              )
            else
              Positioned.fill(
                child: GoogleMap(
                  onMapCreated: _onMapCreated,
                  initialCameraPosition: CameraPosition(target: target, zoom: 15),
                  // 🔴 全部手势关掉 —— 它是一张"活的图片"（B1-D9：只显示位置、不可浏览搜索）。
                  // 顺带避免在可滚动页面里跟页面抢手势。
                  zoomGesturesEnabled: false,
                  scrollGesturesEnabled: false,
                  rotateGesturesEnabled: false,
                  tiltGesturesEnabled: false,
                  zoomControlsEnabled: false,
                  // 🛡 mapToolbar 会直接跳 Google 地图的**路线规划**（Directions，按次计费的那类），
                  // 这里必须关 —— 我们自己的「在地图中打开」是普通跳转链接。
                  mapToolbarEnabled: false,
                  // 定位权限统一走 permission_handler，不让地图 SDK 自己去要。
                  myLocationEnabled: false,
                  myLocationButtonEnabled: false,
                  liteModeEnabled: true,
                  markers: {
                    Marker(markerId: const MarkerId('placeLocation'), position: target),
                  },
                  // 点地图任意处 = 跳系统地图（整块都是热区，比只点右下角那个小标签好按）。
                  onTap: (_) => onOpenExternal(),
                  gestureRecognizers: {
                    Factory<OneSequenceGestureRecognizer>(TapGestureRecognizer.new),
                  },
                ),
              ),
            Positioned(
              right: AppSpacing.sm,
              bottom: AppSpacing.sm,
              child: Material(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(8),
                // UI 稿 A4：胶囊浮在地图上要一点轻阴影。
                elevation: 2,
                shadowColor: Colors.black26,
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  key: const ValueKey('placeDetailOpenInMaps'),
                  onTap: onOpenExternal,
                  child: Padding(
                    // 竖向 10 + 文本 ≈ 高度足够；横向留出文字（UX-DR16 热区）。
                    padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.md, vertical: 10),
                    child: Row(
                      children: [
                        Text(l10n.placeDetailOpenInMaps,
                            style: AppTypography.micro.copyWith(
                                color: AppColors.mint, fontWeight: FontWeight.w700)),
                        const Icon(Icons.chevron_right_rounded,
                            size: 14, color: AppColors.mint),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 安卓返回卡顿（bug 20260925-572）的守卫：**先撤掉原生地图，再开始返回动画**。
///
/// 实测（2026-09-29，模拟器 profile）：返回动画里带着原生地图（platform view），
/// 每帧都要把它一起合成，卡顿帧 18%～36%、单帧渲染最高 125ms；去掉地图后卡顿帧归零。
/// 而销毁地图本身只要 25～33ms（约两帧）—— 所以不在动画里扛着它，先撤再走。
///
/// 做法：拦住返回 → 通知 [PlaceMiniMap] 换成截图（看起来没变）→ 等两帧让原生控件真正销毁
/// → 再 pop。返回键与返回手势都走这里；代码里直接 `Navigator.pop` 不经过它，不受影响。
///
/// 🔒 **只对安卓生效**：iOS 上 `canPop: false` 会让左缘右滑返回失效，那是明显退化，
/// 而本问题只在安卓上被报。代价：本页没有安卓 14+ 的预测性返回预览（松手后才开始返回）。
class PlaceMiniMapPopGuard extends StatefulWidget {
  const PlaceMiniMapPopGuard({super.key, required this.child});

  final Widget child;

  /// 子树里的 [PlaceMiniMap] 用它监听「该撤图了」；拿到 null = 不在守卫下（iOS / 其它页）。
  static ValueListenable<bool>? _retiredOf(BuildContext context) => context
      .dependOnInheritedWidgetOfExactType<_RetireScope>()
      ?.retired;

  @override
  State<PlaceMiniMapPopGuard> createState() => _PlaceMiniMapPopGuardState();
}

class _PlaceMiniMapPopGuardState extends State<PlaceMiniMapPopGuard> {
  final ValueNotifier<bool> _retired = ValueNotifier(false);
  bool _popping = false;

  @override
  void dispose() {
    _retired.dispose();
    super.dispose();
  }

  Future<void> _retireThenPop(bool didPop, Object? result) async {
    if (didPop || _popping) return;
    _popping = true;
    _retired.value = true;
    // 第一帧：地图换成截图、原生控件出树；第二帧：原生侧销毁完成。
    await WidgetsBinding.instance.endOfFrame;
    await WidgetsBinding.instance.endOfFrame;
    if (mounted) Navigator.of(context).pop(result);
  }

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) return widget.child;
    return PopScope<Object?>(
      canPop: false,
      onPopInvokedWithResult: _retireThenPop,
      child: _RetireScope(retired: _retired, child: widget.child),
    );
  }
}

class _RetireScope extends InheritedWidget {
  const _RetireScope({required this.retired, required super.child});

  final ValueNotifier<bool> retired;

  @override
  bool updateShouldNotify(_RetireScope old) => old.retired != retired;
}

