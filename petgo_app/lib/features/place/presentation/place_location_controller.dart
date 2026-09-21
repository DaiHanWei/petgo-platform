import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/location_service.dart';

/// 定位态（V1.3.0 batch-b1 Story 1.2 · AC1/AC5）。不可变。
class PlaceLocationState {
  const PlaceLocationState({required this.permission, this.coordinates});

  final LocationPermissionOutcome permission;

  /// 非空 = **真的拿到了坐标**。
  ///
  /// 🔴 `permission == granted` 但 `coordinates == null` 是常态而不是异常：
  /// 系统定位总开关关着、室内 8 秒内没定点，都会落到这里。
  /// 判「能不能按距离排」一律看这个字段，**不要看权限**。
  final DeviceCoordinates? coordinates;

  /// 顶部「开启定位」提示条要不要出（AC5）。
  ///
  /// 已授权但暂时没定点时**不出提示条** —— 那会把「GPS 还没定上」说成「你没给权限」，
  /// 用户点进设置会发现权限明明是开着的。
  bool get needsPermissionBanner => permission != LocationPermissionOutcome.granted;

  /// 永久拒绝：按钮要跳系统设置，再 `request()` 一次系统也不会弹窗。
  bool get mustGoToSettings =>
      permission == LocationPermissionOutcome.permanentlyDenied;

  PlaceLocationState copyWith(
          {LocationPermissionOutcome? permission, DeviceCoordinates? coordinates}) =>
      PlaceLocationState(
        permission: permission ?? this.permission,
        coordinates: coordinates ?? this.coordinates,
      );
}

/// 定位态控制器。
///
/// <h2>🔴 「拒绝后不反复弹」是靠结构保证的，不是靠一个标记位</h2>
/// [build] 只读权限**状态**（`status()`，不弹窗）；系统弹窗只可能来自 [requestPermission]，
/// 而它只由用户点「开启定位」触发。所以无论用户拒绝几次、页面进出几次，
/// 都不会出现「一进页面就弹框」—— 不需要 shared_preferences 记一个"问过了"的标记，
/// 也就不存在那个标记被清掉之后又开始骚扰的情况。
class PlaceLocationController extends AsyncNotifier<PlaceLocationState> {
  @override
  Future<PlaceLocationState> build() async {
    ref.onDispose(() {
      _resumeListener?.dispose();
      _resumeListener = null;
    });
    final gateway = ref.read(locationGatewayProvider);
    final outcome = await gateway.status();
    if (outcome != LocationPermissionOutcome.granted) {
      return PlaceLocationState(permission: outcome);
    }
    return PlaceLocationState(
      permission: outcome,
      coordinates: await gateway.currentCoordinates(),
    );
  }

  /// 用户点「开启定位」。返回本次结果，供页面决定是否引导去系统设置。
  Future<LocationPermissionOutcome> requestPermission() async {
    final gateway = ref.read(locationGatewayProvider);
    final outcome = await gateway.request();
    final coords = outcome == LocationPermissionOutcome.granted
        ? await gateway.currentCoordinates()
        : null;
    state = AsyncValue.data(
        PlaceLocationState(permission: outcome, coordinates: coords));
    return outcome;
  }

  /// 跳系统设置（永久拒绝后的唯一出路）。
  ///
  /// 🔴 **回到 App 时重读权限**（batch-b1 复审）：用户去设置里开了权限回来，页面一直都在
  /// （没有离开 → autoDispose 不会触发重建），状态里仍是「永久拒绝」—— 提示条不消失、
  /// 列表仍按最新排、再点按钮又跳设置，用户原地打转。
  /// 所以挂一个一次性的前台回调：回来即 [Ref.invalidateSelf]，build 重读 `status()`（不弹窗）。
  Future<void> openSettings() async {
    _resumeListener?.dispose();
    final listener = AppLifecycleListener(onResume: () {
      _resumeListener?.dispose();
      _resumeListener = null;
      if (ref.mounted) ref.invalidateSelf();
    });
    _resumeListener = listener;
    await ref.read(locationGatewayProvider).openSettings();
  }

  AppLifecycleListener? _resumeListener;
}

/// 🔴 **必须 `isAutoDispose: true`**：定位态里存着坐标与权限状态，两者都会在页面之外变化 ——
/// 用户走了几公里再进来、或者去系统设置里开了权限回来。
/// Riverpod 3 的 legacy `AsyncNotifierProvider` **默认 `isAutoDispose: false`**，
/// 那样整个 App 生命周期只 build 一次：距离永远按第一次的位置算，
/// 而提示条在权限已经打开之后还一直挂着，直到用户手动下拉刷新。
final AsyncNotifierProvider<PlaceLocationController, PlaceLocationState>
    placeLocationProvider =
    AsyncNotifierProvider<PlaceLocationController, PlaceLocationState>(
        PlaceLocationController.new,
        isAutoDispose: true);
