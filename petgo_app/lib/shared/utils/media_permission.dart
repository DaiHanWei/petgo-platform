import 'dart:io' show Platform;

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

import '../../l10n/app_localizations.dart';

/// 上传来源：相机 / 相册。
enum MediaSource { camera, gallery }

/// 权限申请结果（状态机三态）。
enum MediaPermissionOutcome { granted, denied, permanentlyDenied }

/// 权限申请边界（薄接口）。把 `permission_handler` 静态调用与状态机/引导逻辑解耦，
/// 便于 L0 注入 fake（headless 无系统权限弹窗）。
abstract class PermissionGateway {
  Future<MediaPermissionOutcome> request(MediaSource source);

  /// 跳系统设置页（「去设置」深链）。
  Future<bool> openSettings();
}

/// 真实实现（运行期路径）。
class PermissionHandlerGateway implements PermissionGateway {
  const PermissionHandlerGateway();

  @override
  Future<MediaPermissionOutcome> request(MediaSource source) async {
    final permission = await _resolvePermission(source);
    final status = await permission.request();
    if (status.isGranted || status.isLimited) {
      return MediaPermissionOutcome.granted;
    }
    if (status.isPermanentlyDenied || status.isRestricted) {
      return MediaPermissionOutcome.permanentlyDenied;
    }
    return MediaPermissionOutcome.denied;
  }

  /// 把「来源」映射到具体系统权限。相册必须按 Android 版本分流：
  /// `Permission.photos` 仅对应 Android 13+(SDK 33) 的 `READ_MEDIA_IMAGES`，在 ≤12 上
  /// permission_handler 拿到空权限列表 → 不弹窗、直接 permanentlyDenied，而系统设置页又
  /// 只暴露 Storage、无 Photos 项 → 用户永远开不了相册（死循环 bug）。≤12 必须改用
  /// `Permission.storage`(READ_EXTERNAL_STORAGE，manifest 已带 maxSdkVersion=32)。iOS 恒用 photos。
  Future<ph.Permission> _resolvePermission(MediaSource source) async {
    if (source == MediaSource.camera) return ph.Permission.camera;
    if (Platform.isAndroid && await _androidSdkInt() < 33) {
      return ph.Permission.storage;
    }
    return ph.Permission.photos;
  }

  Future<int> _androidSdkInt() async {
    final info = await DeviceInfoPlugin().androidInfo;
    return info.version.sdkInt;
  }

  @override
  Future<bool> openSettings() => ph.openAppSettings();
}

/// 拒绝引导对话框（FR-22D）：「需要相册/相机权限才能上传，请前往设置开启」+「去设置」。
/// 文案走 .arb（id/en），无写死字符串。点「去设置」调 [PermissionGateway.openSettings]。
Future<void> showMediaPermissionDeniedDialog(
  BuildContext context,
  PermissionGateway gateway,
) async {
  final l10n = AppLocalizations.of(context);
  await showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(l10n.mediaPermissionDeniedTitle),
      content: Text(l10n.mediaPermissionDeniedBody),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(),
          child: Text(l10n.commonCancel),
        ),
        FilledButton(
          onPressed: () {
            Navigator.of(ctx).pop();
            gateway.openSettings();
          },
          child: Text(l10n.mediaOpenSettings),
        ),
      ],
    ),
  );
}
