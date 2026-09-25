import 'package:flutter/foundation.dart' show Factory;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/location_service.dart';

/// 地图选点弹层（V1.3.0 batch-b1 Story 1.4 · UI 稿 A6 · AD-3 / B1-D9）。
///
/// <h2>🔴 SDK 使用边界（AD-3 Rule 1/2）—— 越界即计费</h2>
/// 本文件**只用两件事**：
/// <ol>
///   <li>显示地图（缩放拖动）；</li>
///   <li>把一个我们自己持有的经纬度渲染成标记。</li>
/// </ol>
/// **没有、也绝不能加**：地点搜索 / 自动补全（Places）、地址与坐标互转（Geocoding）、
/// 路线规划（Directions）。<b>那三类才是按次收费的大头</b>，而 PRD ① 明定
/// 「平台不做地理编码」，所以我们天然不需要它们。
/// 移动端原生地图**显示**本身免费且不限量（2026-09-11 核实官方价目）。
/// 谁要加，先回 AD-3 改口径 —— 而且 `map_usage_boundary_test.dart` 会先红。
///
/// <h2>地图只出现在两处（AD-3 Rule 3 / AC5）</h2>
/// 选点弹层（本文件）与场所详情页的定位小地图（Story 1.5）。
/// **列表页不得嵌地图** —— 那是 PRD ⑥ 明确排除的「地图浏览视图」。
///
/// <h2>与 Story 1.3 的衔接</h2>
/// 1.3 交付时位置来源是「当前定位」。本 story **替换**那条路径为选点弹层，
/// 但**保留「默认落当前位置」** —— 打开地图时针就在你所在处，不动它直接确认 = 与 1.3 等价。
class PlaceMapPickerSheet extends StatefulWidget {
  const PlaceMapPickerSheet({super.key, this.initial, this.onEnableLocation});

  /// 打开时大头针落在哪。null = 没有定位权限 / 没拿到定点 → 落 [jakartaCenter]。
  final DeviceCoordinates? initial;

  /// 未授权定位时由调用方给出（bug 20260921-508）：弹层顶部出「开启定位」提示条，
  /// 点了走调用方那条唯一的权限路径（系统弹窗 / 永久拒绝跳设置），返回拿到的坐标（拿不到为 null）。
  /// null = 已授权或调用方不需要 → 不出提示条。
  /// 🔴 提示条只是**建议**：不开定位照样能拖针手动选点（Story 1.4 AC4「无权限也能选点」）。
  final Future<DeviceCoordinates?> Function()? onEnableLocation;

  /// 无定位兜底落点：雅加达市中心（Monas 一带）。
  ///
  /// 🔴 **不能留空白地图**（AC4）：Google 地图在没有目标坐标时会落到一片海或世界视图，
  /// 用户看到的是「这功能坏了」。冷启动数据本来就是雅加达（PRD ⑤），落这里也最可能就近。
  static const LatLng jakartaCenter = LatLng(-6.1754, 106.8272);

  /// 打开弹层，返回用户确认的坐标；取消返回 null。
  static Future<DeviceCoordinates?> open(
      BuildContext context, DeviceCoordinates? initial,
      {Future<DeviceCoordinates?> Function()? onEnableLocation}) {
    return showModalBottomSheet<DeviceCoordinates>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      // 🔴 **必须关掉下拉关闭**（code-review 2026-09-15）：弹层的竖向拖拽手势会在手势竞技场
      // 里赢过地图 —— 用户想竖向平移地图或拖那个针，结果是把弹层拖走了，`onDragEnd` 永远不触发。
      // 关闭走右上角的 ✕（已给 44×44 热区）。
      enableDrag: false,
      builder: (_) =>
          PlaceMapPickerSheet(initial: initial, onEnableLocation: onEnableLocation),
    );
  }

  @override
  State<PlaceMapPickerSheet> createState() => _PlaceMapPickerSheetState();
}

class _PlaceMapPickerSheetState extends State<PlaceMapPickerSheet> {
  late LatLng _picked = widget.initial == null
      ? PlaceMapPickerSheet.jakartaCenter
      : LatLng(widget.initial!.latitude, widget.initial!.longitude);

  /// 用户有没有真的动过针。
  ///
  /// 🔴 **没有定位时不许直接确认**（code-review 2026-09-15）：那种情况下针预置在雅加达市中心，
  /// 用户一进来就点「确认位置」→ 静默把 Monas 当成了这家店的坐标。
  /// 而场所**既不能编辑也不能删除** —— 这是一条永久的坏数据。
  /// 有定位时预置点就是用户所在处，直接确认是有意义的（= 与 Story 1.3 等价），所以那时不设门槛。
  bool _moved = false;

  bool get _canConfirm => widget.initial != null || _moved || _gotFix;

  /// 在弹层里点「开启定位」后真的拿到了定点（针已移到用户所在处）→ 与「打开时就有定位」等价。
  bool _gotFix = false;

  bool _enabling = false;

  GoogleMapController? _map;

  bool get _showLocationBanner =>
      widget.onEnableLocation != null && widget.initial == null && !_gotFix;

  Future<void> _enableLocation() async {
    setState(() => _enabling = true);
    final coords = await widget.onEnableLocation!();
    if (!mounted) return;
    setState(() {
      _enabling = false;
      if (coords != null && !_moved) {
        // 用户还没自己选过点 → 针跳到他所在处；已经手动选过就不覆盖他的选择。
        _picked = LatLng(coords.latitude, coords.longitude);
        _gotFix = true;
      } else if (coords != null) {
        _gotFix = true;
      }
    });
    if (coords != null && !_moved) {
      await _map?.animateCamera(CameraUpdate.newLatLngZoom(_picked, 16));
    }
  }

  @override
  void dispose() {
    _map?.dispose();
    super.dispose();
  }

  /// 选点标记的 id。固定一个 —— 弹层里**只会有一个针**（这是选点而不是浏览）。
  static const MarkerId _pinId = MarkerId('placePickerPin');

  /// 无定位兜底时的初始缩放。
  ///
  /// 比有定位时（16，街道级）**刻意拉远**：市中心那个点只是个起点、不是建议值，
  /// 街道级缩放会让它看起来像「系统已经帮你定好了」。
  double get _initialZoom => widget.initial == null ? 12 : 16;

  void _move(LatLng p) => setState(() {
        _picked = p;
        _moved = true;
      });

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return FractionallySizedBox(
      heightFactor: 0.9,
      child: Column(
        children: [
          _SheetHeader(title: l10n.placeMapPickerTitle),
          if (_showLocationBanner)
            _PickerLocationBanner(
              busy: _enabling,
              onEnable: _enabling ? null : _enableLocation,
            ),
          // 🔴 **确认栏不叠在地图上**（UI 稿 A6 · Google 署名合规）：原先确认栏
          // Positioned 在 Stack 里，正好盖住左下角的 Google logo —— 地图 SDK 条款要求
          // 署名不得遮挡。改成地图下方独立的白色底栏，地图本身完整可见。
          Expanded(
            child: GoogleMap(
              initialCameraPosition:
                  CameraPosition(target: _picked, zoom: _initialZoom),
              onMapCreated: (c) => _map = c,
              // 🔴 **地图必须在手势竞技场里抢到手势**：外面是个 BottomSheet，
              // 不给 EagerGestureRecognizer 的话竖向平移与拖针都会被弹层的拖拽手势吃掉。
              gestureRecognizers: {
                Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
              },
              // 🛡 只显示 + 打点。刻意关掉的：
              //   - myLocationEnabled：蓝点需要地图 SDK 自己去要定位权限，而权限统一
              //     走 permission_handler（全 App 一条路径，见 location_service.dart）；
              //   - mapToolbarEnabled：那个工具条会直接跳 Google 地图的路线规划；
              //   - zoomControlsEnabled：手势够用，按钮挤掉地图面积。
              myLocationEnabled: false,
              myLocationButtonEnabled: false,
              mapToolbarEnabled: false,
              zoomControlsEnabled: false,
              markers: {
                Marker(
                  markerId: _pinId,
                  position: _picked,
                  draggable: true,
                  // 拖完才更新（onDrag 每帧都回调，setState 会把地图拖出卡顿）。
                  onDragEnd: _move,
                ),
              },
              // 点地图任意处也能移针 —— 拖那个小针在手机上并不好按。
              onTap: _move,
            ),
          ),
          _ConfirmBar(
            hint: _canConfirm
                ? l10n.placeMapPickerHint
                : l10n.placeMapPickerNeedsPick,
            label: l10n.placeMapPickerConfirm,
            // 无定位且还没动过针 → 禁用（否则一进来点确认就把市中心当成了店址）。
            onConfirm: _canConfirm
                ? () => Navigator.of(context).pop(
                      DeviceCoordinates(
                          latitude: _picked.latitude,
                          longitude: _picked.longitude),
                    )
                : null,
          ),
        ],
      ),
    );
  }
}

/// 未授权定位时的提示条（bug 20260921-508）。样式与场所列表页「开启定位」提示条同一套。
class _PickerLocationBanner extends StatelessWidget {
  const _PickerLocationBanner({required this.busy, required this.onEnable});

  final bool busy;
  final VoidCallback? onEnable;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('placeMapPickerLocationBanner'),
      margin: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.xs, AppSpacing.lg, AppSpacing.sm),
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.xs),
      decoration: BoxDecoration(
        color: AppColors.goldTint,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          const Icon(Icons.my_location_rounded, size: 16, color: AppColors.tipsBadgeText),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(l10n.placeMapPickerLocationHint,
                style: AppTypography.caption.copyWith(color: AppColors.tipsBadgeText)),
          ),
          // 44×44 热区（UX-DR16）。
          if (busy)
            const Padding(
              padding: EdgeInsets.all(AppSpacing.md),
              child: SizedBox(
                  width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
            )
          else
            TextButton(
              key: const ValueKey('placeMapPickerEnableLocation'),
              onPressed: onEnable,
              style: TextButton.styleFrom(
                minimumSize: const Size(44, 44),
                foregroundColor: AppColors.tipsBadgeText,
              ),
              child: Text(l10n.placeLocationEnable,
                  style: AppTypography.caption.copyWith(
                      color: AppColors.tipsBadgeText, fontWeight: FontWeight.w700)),
            ),
        ],
      ),
    );
  }
}

class _SheetHeader extends StatelessWidget {
  const _SheetHeader({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.sm, AppSpacing.sm, 0),
      child: Row(
        children: [
          Expanded(child: Text(title, style: AppTypography.title)),
          IconButton(
            key: const ValueKey('placeMapPickerClose'),
            // 44×44 热区（UX-DR16）。
            constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
            onPressed: () => Navigator.of(context).pop(),
            icon: const Icon(Icons.close),
          ),
        ],
      ),
    );
  }
}

class _ConfirmBar extends StatelessWidget {
  const _ConfirmBar(
      {required this.hint, required this.label, required this.onConfirm});

  final String hint;
  final String label;

  /// null = 禁用（灰）。无定位且用户还没动过针时就是这样。
  final VoidCallback? onConfirm;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppColors.card,
      // 底部补系统手势条高度（弹层的 useSafeArea 只让出顶部）。
      padding: EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.md, AppSpacing.lg,
          AppSpacing.lg + MediaQuery.paddingOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 🛡 **不显示坐标数值**：对用户没有意义，而且那是 PII（NFR-4 同一条精神）。
          Text(hint, style: AppTypography.caption, textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.sm),
          SizedBox(
            width: double.infinity,
            height: 48,
            child: FilledButton(
              key: const ValueKey('placeMapPickerConfirm'),
              onPressed: onConfirm,
              child: Text(label),
            ),
          ),
        ],
      ),
    );
  }
}
