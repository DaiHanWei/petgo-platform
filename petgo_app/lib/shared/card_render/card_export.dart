import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:gal/gal.dart';
import 'package:share_plus/share_plus.dart';

import '../../l10n/app_localizations.dart';

/// 出图后的落地动作：**存相册 / 系统分享**（做法照抄身份证导出链路，AD-15 Rule 1b）。
///
/// ⚠️ 本文件只提供能力，**不接任何入口** —— 谁在哪儿点了分享是 9-3 的事。
class CardExport {
  CardExport._();

  /// 存相册。失败一律走文案兜底（多半是相册权限没给）。
  static Future<bool> saveToGallery(Uint8List bytes, {required String name}) async {
    try {
      await Gal.putImageBytes(bytes, name: name);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 拉起系统分享面板。
  ///
  /// [origin] 是 iPad 上分享气泡的锚点矩形（不传会在 iPad 上抛异常），
  /// 调用方按身份证那边的做法取宿主 `RenderBox` 的全局矩形。
  ///
  /// 返回**归一化后的渠道**（`whatsapp` / `instagram` / `other`），
  /// 用户取消或平台不给回调时返回 `null`。
  static Future<String?> shareImage(
    Uint8List bytes, {
    required String name,
    Rect? origin,
  }) async {
    final result = await Share.shareXFiles(
      [XFile.fromData(bytes, name: '$name.png', mimeType: 'image/png')],
      sharePositionOrigin: origin,
    );
    if (result.status != ShareResultStatus.success) {
      return null; // 取消 / 不可用 —— 没分享出去就不该记一笔"分享成功"
    }
    return normalizeChannel(result.raw);
  }

  /// 把系统回传的原始标识归一到埋点词表（E-13 的 `channel`）。
  ///
  /// 原始值两个平台形状完全不同（Android 给包名 `com.whatsapp`，
  /// iOS 给 activity type `net.whatsapp.WhatsApp.ShareExtension`），
  /// 所以按**子串**匹配而不是等值匹配。
  ///
  /// ⚠️ 这个属性**覆盖不全、不可当分母**（清单 §3 原话）：部分平台压根不回调，
  /// 那种情况上游会拿到空串 → 归一为 `other`。要看"分享了多少次"用事件条数，
  /// 要看"走哪个渠道"才看这个属性的**相对比例**。
  @visibleForTesting
  static String normalizeChannel(String? raw) {
    final v = (raw ?? '').toLowerCase();
    if (v.contains('whatsapp')) return 'whatsapp';
    if (v.contains('instagram')) return 'instagram';
    return 'other';
  }

  /// 「存相册 / 分享」二选一选单（版式与身份证那套一致，保持体感统一）。
  /// [onShared] 在**系统面板回调分享成功之后**被调用一次，参数是归一化渠道。
  /// 本文件仍然不认识任何埋点事件名 —— 报什么由调用方决定（AD-15 Rule 1b 的边界不变）。
  static Future<void> showSheet(
    BuildContext context, {
    required Uint8List bytes,
    required String name,
    Rect? shareOrigin,
    void Function(String channel)? onShared,
  }) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    await showModalBottomSheet<void>(
      context: context,
      builder: (sheetCtx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              key: const ValueKey('cardSaveToGallery'),
              leading: const Icon(Icons.download_rounded),
              title: Text(l10n.cardSaveToGallery),
              onTap: () async {
                Navigator.of(sheetCtx).pop();
                final ok = await saveToGallery(bytes, name: name);
                messenger.showSnackBar(SnackBar(
                  content: Text(ok ? l10n.cardSavedToGallery : l10n.cardSaveError),
                ));
              },
            ),
            ListTile(
              key: const ValueKey('cardShareImage'),
              leading: const Icon(Icons.ios_share_rounded),
              title: Text(l10n.cardShareImage),
              onTap: () async {
                Navigator.of(sheetCtx).pop();
                final channel =
                    await shareImage(bytes, name: name, origin: shareOrigin);
                if (channel != null) onShared?.call(channel);
              },
            ),
          ],
        ),
      ),
    );
  }
}
