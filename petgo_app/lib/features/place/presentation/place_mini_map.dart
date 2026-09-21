import 'package:flutter/foundation.dart' show Factory;
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
class PlaceMiniMap extends StatelessWidget {
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
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final target = LatLng(latitude, longitude);
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: SizedBox(
        height: _height,
        child: Stack(
          children: [
            Positioned.fill(
              child: GoogleMap(
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
