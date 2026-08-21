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
  static Future<void> shareImage(
    Uint8List bytes, {
    required String name,
    Rect? origin,
  }) async {
    await Share.shareXFiles(
      [XFile.fromData(bytes, name: '$name.png', mimeType: 'image/png')],
      sharePositionOrigin: origin,
    );
  }

  /// 「存相册 / 分享」二选一选单（版式与身份证那套一致，保持体感统一）。
  static Future<void> showSheet(
    BuildContext context, {
    required Uint8List bytes,
    required String name,
    Rect? shareOrigin,
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
              onTap: () {
                Navigator.of(sheetCtx).pop();
                shareImage(bytes, name: name, origin: shareOrigin);
              },
            ),
          ],
        ),
      ),
    );
  }
}
