/// 设备定位（V1.3.0 batch-b1 Story 1.2 · AC1/AC5/AC6）。
///
/// <h2>🛡 红线</h2>
/// - **经纬度只随请求发给自家后端**：绝不进日志、绝不进埋点属性（NFR-4 / NFR-5）。
///   埋点层有兜底黑名单会整条丢弃，但不要依赖兜底 —— 源头就不要传。
/// - **只取一次当前位置**：不做后台定位、不做持续追踪（没有 `getPositionStream`）、不做地理围栏。
/// - **不用 geolocator 自带的权限方法**：权限统一走 `permission_handler`，与
///   `shared/utils/media_permission.dart` 同一条路径。全 App 只保留一套权限申请写法。
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

/// 定位权限三态（与 `MediaPermissionOutcome` 同形）。
enum LocationPermissionOutcome {
  granted,

  /// 本次被拒（可以再问）。
  denied,

  /// 永久拒绝 / 受限：**再问也不会弹窗**，只能去系统设置。
  permanentlyDenied,
}

/// 一次定位的结果。
///
/// 🔴 `latitude`/`longitude` 是敏感值：**不要**给这个类写 `toString()` / 塞进日志。
class DeviceCoordinates {
  const DeviceCoordinates({required this.latitude, required this.longitude});

  final double latitude;
  final double longitude;
}

/// 定位能力边界（薄接口）。把插件静态调用与状态机解耦，便于 L0 注入 fake
/// （headless 环境没有系统权限弹窗、也没有 GPS）。
abstract class LocationGateway {
  /// 读当前权限状态，**不弹窗**。
  ///
  /// 🔴 页面打开时只能调这个 —— 直接 `request()` 就是「一进页面就弹系统权限框」，
  /// 而 AC5 要求提示条 +「开启定位」按钮才触发申请。
  Future<LocationPermissionOutcome> status();

  /// 申请权限（会弹系统窗）。只允许由用户点「开启定位」触发。
  Future<LocationPermissionOutcome> request();

  /// 取一次当前坐标。拿不到（超时 / 定位服务关闭 / 无权限）→ null。
  Future<DeviceCoordinates?> currentCoordinates();

  /// 跳系统设置页（永久拒绝后的唯一出路）。
  Future<bool> openSettings();
}

/// 真实实现（运行期路径）。
class GeolocatorLocationGateway implements LocationGateway {
  const GeolocatorLocationGateway();

  /// 取位置的超时。
  ///
  /// 冷启动定位在室内可能很久才有第一个定点；超时没拿到就按「没有坐标」走按最新分支，
  /// **不要无限等** —— 列表会一直空白转圈，而用户根本不知道在等 GPS。
  ///
  /// ⚠️ 5 秒是**用户能忍的空屏上限**换来的，不是定位成功率最优值：这段时间列表页只有转圈，
  /// 连按最新的列表都看不到。要再放长之前先想清楚「多等 3 秒换一个排序」值不值。
  static const Duration _fixTimeout = Duration(seconds: 5);

  /// 系统缓存定点的**最大可接受年龄**。
  ///
  /// 🔴 `getLastKnownPosition()` 可能返回几小时甚至几天前的定点（**甚至是另一个城市的**）——
  /// 那会把雅加达的店在泗水显示成「1,2 km」。10 分钟内的缓存对「按距离排序」够用
  /// （人不会瞬移，列表精度也只到百米级），更老的一律不要。
  static const Duration _maxCacheAge = Duration(minutes: 10);

  /// 只申请「使用期间」定位（`ACCESS_FINE_LOCATION` / `NSLocationWhenInUseUsageDescription`）。
  /// 🛡 **绝不申请 always/后台定位** —— 按距离排序只需要用户正在看列表的那一刻。
  static const ph.Permission _permission = ph.Permission.locationWhenInUse;

  @override
  Future<LocationPermissionOutcome> status() async => _map(await _permission.status);

  @override
  Future<LocationPermissionOutcome> request() async => _map(await _permission.request());

  static LocationPermissionOutcome _map(ph.PermissionStatus s) {
    if (s.isGranted || s.isLimited) return LocationPermissionOutcome.granted;
    if (s.isPermanentlyDenied || s.isRestricted) {
      return LocationPermissionOutcome.permanentlyDenied;
    }
    return LocationPermissionOutcome.denied;
  }

  @override
  Future<DeviceCoordinates?> currentCoordinates() async {
    try {
      // 系统定位总开关是关着的话，取位置会一直等 —— 先问一句，关着就直接放弃。
      if (!await Geolocator.isLocationServiceEnabled()) return null;

      // 🔴 先用系统缓存的上一个定点（立刻返回），但**必须查它多老**。
      // 冷启动在室内要等好几秒才有第一个定点，那几秒里列表只能空转 —— 而「按距离排序」
      // 用一个几分钟前的位置完全够用（人不会瞬移，列表精度也只到百米级）。
      // ⚠️ 不查时间戳就用是个真实的错：缓存定点可能是几天前在另一个城市留下的。
      final cached = await Geolocator.getLastKnownPosition();
      if (cached != null && _isFreshEnough(cached.timestamp)) {
        return DeviceCoordinates(latitude: cached.latitude, longitude: cached.longitude);
      }

      final pos = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          // medium 足够：列表显示到「1.2 km」这个精度，没必要为几十米去等高精度定点。
          accuracy: LocationAccuracy.medium,
          timeLimit: _fixTimeout,
        ),
      );
      return DeviceCoordinates(latitude: pos.latitude, longitude: pos.longitude);
    } catch (_) {
      // 🔴 **不记日志**：异常信息里可能带上次的坐标。拿不到位置就是拿不到，
      //    调用方回落按最新分支即可。
      return null;
    }
  }

  /// 缓存定点够不够新。
  ///
  /// 时间戳来自系统，理论上可能比本机时钟还"新"（时钟被改过）—— 负的年龄一律当**不新鲜**处理，
  /// 因为那说明两个时间基准对不上，此时无法判断它到底多老。
  static bool _isFreshEnough(DateTime timestamp) {
    final age = DateTime.now().difference(timestamp);
    return !age.isNegative && age <= _maxCacheAge;
  }

  @override
  Future<bool> openSettings() => ph.openAppSettings();
}

final locationGatewayProvider =
    Provider<LocationGateway>((ref) => const GeolocatorLocationGateway());
