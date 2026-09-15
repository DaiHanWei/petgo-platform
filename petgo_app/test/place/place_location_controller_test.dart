import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/presentation/place_location_controller.dart';

/// V1.3.0 batch-b1 Story 1.2 · L0：定位权限状态机（AC5 的可静态验证部分）。
///
/// 🔴 这组用例守的核心是「**拒绝后不反复弹**」：控制器初始化时只允许调 `status()`
/// （不弹窗），系统弹窗只能来自 `requestPermission()`。这条约束在这里是**可证伪的** ——
/// fake 会数清楚 `request()` 被调了几次。
class _FakeLocationGateway implements LocationGateway {
  _FakeLocationGateway({
    required this.initialStatus,
    this.afterRequest,
    this.coordinatesAvailable = true,
  });

  /// 雅加达 Senopati 一带 —— 固定值即可，这组用例不关心具体坐标。
  static const DeviceCoordinates coordinates =
      DeviceCoordinates(latitude: -6.235, longitude: 106.81);

  final LocationPermissionOutcome initialStatus;

  /// `request()` 之后的结果；null = 与 [initialStatus] 相同。
  final LocationPermissionOutcome? afterRequest;

  /// false = 权限有了但拿不到定点（定位总开关关着 / 室内超时）。
  final bool coordinatesAvailable;

  int statusCalls = 0;
  int requestCalls = 0;
  int coordinateCalls = 0;
  int settingsCalls = 0;

  LocationPermissionOutcome _current = LocationPermissionOutcome.denied;
  bool _requested = false;

  @override
  Future<LocationPermissionOutcome> status() async {
    statusCalls++;
    _current = _requested ? (afterRequest ?? initialStatus) : initialStatus;
    return _current;
  }

  @override
  Future<LocationPermissionOutcome> request() async {
    requestCalls++;
    _requested = true;
    _current = afterRequest ?? initialStatus;
    return _current;
  }

  @override
  Future<DeviceCoordinates?> currentCoordinates() async {
    coordinateCalls++;
    return coordinatesAvailable ? coordinates : null;
  }

  @override
  Future<bool> openSettings() async {
    settingsCalls++;
    return true;
  }
}

ProviderContainer _container(_FakeLocationGateway gateway) {
  final c = ProviderContainer(
    overrides: [locationGatewayProvider.overrideWithValue(gateway)],
  );
  addTearDown(c.dispose);
  return c;
}

void main() {
  test('未授权时初始化：只读状态，绝不弹系统窗，也不去取坐标', () async {
    final gateway =
        _FakeLocationGateway(initialStatus: LocationPermissionOutcome.denied);
    final c = _container(gateway);

    final state = await c.read(placeLocationProvider.future);

    expect(state.permission, LocationPermissionOutcome.denied);
    expect(state.coordinates, isNull);
    expect(state.needsPermissionBanner, isTrue);
    expect(gateway.requestCalls, 0,
        reason: '🔴 一进页面就 request() 就是「一进页面弹权限框」—— AC5 要求由用户点按钮触发');
    expect(gateway.coordinateCalls, 0, reason: '没权限还去取坐标是白跑一趟');
  });

  test('已授权时初始化：直接拿到坐标，不出提示条', () async {
    final gateway =
        _FakeLocationGateway(initialStatus: LocationPermissionOutcome.granted);
    final c = _container(gateway);

    final state = await c.read(placeLocationProvider.future);

    expect(state.coordinates, isNotNull);
    expect(state.needsPermissionBanner, isFalse);
    expect(gateway.requestCalls, 0);
  });

  test('已授权但拿不到定点：不出提示条（那会把「GPS 没定上」说成「你没给权限」）', () async {
    final gateway = _FakeLocationGateway(
      initialStatus: LocationPermissionOutcome.granted,
      coordinatesAvailable: false,
    );
    final c = _container(gateway);

    final state = await c.read(placeLocationProvider.future);

    expect(state.permission, LocationPermissionOutcome.granted);
    expect(state.coordinates, isNull, reason: '拿不到定点 → 列表回落按最新');
    expect(state.needsPermissionBanner, isFalse);
  });

  test('点「开启定位」授权成功：弹一次窗、拿到坐标、提示条消失', () async {
    final gateway = _FakeLocationGateway(
      initialStatus: LocationPermissionOutcome.denied,
      afterRequest: LocationPermissionOutcome.granted,
    );
    final c = _container(gateway);
    await c.read(placeLocationProvider.future);

    final outcome =
        await c.read(placeLocationProvider.notifier).requestPermission();

    expect(outcome, LocationPermissionOutcome.granted);
    expect(gateway.requestCalls, 1);
    final state = c.read(placeLocationProvider).value!;
    expect(state.coordinates, isNotNull);
    expect(state.needsPermissionBanner, isFalse);
  });

  test('🔴 拒绝之后：提示条**保留**，且重复点按钮不会累积成自动弹窗', () async {
    final gateway = _FakeLocationGateway(
      initialStatus: LocationPermissionOutcome.denied,
      afterRequest: LocationPermissionOutcome.denied,
    );
    final c = _container(gateway);
    await c.read(placeLocationProvider.future);

    await c.read(placeLocationProvider.notifier).requestPermission();
    final state = c.read(placeLocationProvider).value!;

    expect(state.needsPermissionBanner, isTrue,
        reason: '提示条同时是「为什么这个列表不是按距离排的」的解释，不能拒绝后就消失');
    expect(state.coordinates, isNull);
    expect(gateway.requestCalls, 1, reason: '只有用户点按钮才弹，一次点击一次弹窗');
  });

  test('永久拒绝：按钮应走系统设置（再 request 系统也不会弹窗）', () async {
    final gateway = _FakeLocationGateway(
      initialStatus: LocationPermissionOutcome.permanentlyDenied,
    );
    final c = _container(gateway);

    final state = await c.read(placeLocationProvider.future);
    expect(state.mustGoToSettings, isTrue);
    expect(state.needsPermissionBanner, isTrue);

    await c.read(placeLocationProvider.notifier).openSettings();
    expect(gateway.settingsCalls, 1);
  });

  test('本次拒绝（可再问）不算永久拒绝', () async {
    final gateway =
        _FakeLocationGateway(initialStatus: LocationPermissionOutcome.denied);
    final c = _container(gateway);

    final state = await c.read(placeLocationProvider.future);
    expect(state.mustGoToSettings, isFalse);
  });
}
