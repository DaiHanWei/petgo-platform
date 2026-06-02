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
    final permission = source == MediaSource.camera ? ph.Permission.camera : ph.Permission.photos;
    final status = await permission.request();
    if (status.isGranted || status.isLimited) {
      return MediaPermissionOutcome.granted;
    }
    if (status.isPermanentlyDenied || status.isRestricted) {
      return MediaPermissionOutcome.permanentlyDenied;
    }
    return MediaPermissionOutcome.denied;
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
