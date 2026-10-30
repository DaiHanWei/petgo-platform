import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:gal/gal.dart';
import 'package:permission_handler/permission_handler.dart' as ph;
import 'package:qr_flutter/qr_flutter.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../app_toast.dart';
import '../pay_channel_picker.dart' show formatIdrAmount;
import 'qr_pay_parts.dart';

/// 相册写入边界（薄接口，L0 注入 fake；真实实现走 `gal`）。
abstract class QrPayGallery {
  Future<bool> hasAccess();
  Future<bool> requestAccess();
  Future<void> putImageBytes(Uint8List bytes, {required String name});
  Future<bool> openSettings();
}

class GalQrPayGallery implements QrPayGallery {
  const GalQrPayGallery();

  @override
  Future<bool> hasAccess() => Gal.hasAccess();

  @override
  Future<bool> requestAccess() => Gal.requestAccess();

  @override
  Future<void> putImageBytes(Uint8List bytes, {required String name}) =>
      Gal.putImageBytes(bytes, name: name);

  @override
  Future<bool> openSettings() => ph.openAppSettings();
}

/// 测试注入点：默认真实 `gal`。
@visibleForTesting
QrPayGallery qrPayGallery = const GalQrPayGallery();

/// 保存图片的画布宽（稿 S1：宽 1080px 导出）。
const double kQrPaySaveWidth = 1080;

/// 「Simpan Kode QR」：把二维码 + 金额 + 订单号 + 有效期渲染成 PNG（稿 S1）写入相册。
///
/// - 成功 → Toast「Tersimpan ke galeri」；抽屉 / 页面不关，轮询继续。
/// - 没有相册权限 → 先走系统申请；仍被拒 → App 内「Perlu izin」对话框（只换正文，「Buka Pengaturan」跳系统设置，稿 A3）。
/// - 其他失败 → Toast「Gagal menyimpan…」。
/// - 保存进行中再点：直接忽略（连点会叠出多个权限对话框 / 往相册写多张图）。
Future<void> saveQrPaymentImage(
  BuildContext context, {
  required String payload,
  int? amountIdr,
  String? orderRef,
  DateTime? expiresAt,
}) async {
  if (_saving) return;
  _saving = true;
  try {
    await _save(context, payload: payload, amountIdr: amountIdr, orderRef: orderRef, expiresAt: expiresAt);
  } finally {
    _saving = false;
  }
}

/// 同一时刻全 App 只有一个支付二维码界面，单个标志即可（抽屉 / 充值页 / 问诊页共用）。
bool _saving = false;

Future<void> _save(
  BuildContext context, {
  required String payload,
  int? amountIdr,
  String? orderRef,
  DateTime? expiresAt,
}) async {
  final l10n = AppLocalizations.of(context);
  final locale = Localizations.localeOf(context).languageCode;
  final overlay = Overlay.of(context, rootOverlay: true);
  final gallery = qrPayGallery;
  try {
    var ok = await gallery.hasAccess();
    if (!ok) ok = await gallery.requestAccess();
    if (!ok) {
      if (context.mounted) await _showPermissionDialog(context, l10n, gallery);
      return;
    }
    final bytes = await renderQrPaymentImage(
      payload: payload,
      amountText: amountIdr == null ? null : 'Rp${formatIdrAmount(amountIdr)}',
      orderLine: (orderRef ?? '').isEmpty ? null : '${l10n.orderNumberLabel}: $orderRef',
      validLine: expiresAt == null
          ? null
          : l10n.qrPaySavedValidUntil(qrPayDeadlineText(expiresAt, locale, withYear: true)),
    );
    await gallery.putImageBytes(bytes,
        name: 'tailtopia_qris_${(orderRef ?? '').isEmpty ? DateTime.now().millisecondsSinceEpoch : orderRef}');
    showAppToastOnOverlay(overlay, l10n.cardSavedToGallery, extraBottom: 40);
  } on GalException catch (e) {
    if (e.type == GalExceptionType.accessDenied && context.mounted) {
      await _showPermissionDialog(context, l10n, gallery);
      return;
    }
    showAppToastOnOverlay(overlay, l10n.cardSaveError, extraBottom: 40);
  } catch (_) {
    showAppToastOnOverlay(overlay, l10n.cardSaveError, extraBottom: 40);
  }
}

Future<void> _showPermissionDialog(BuildContext context, AppLocalizations l10n, QrPayGallery gallery) =>
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const ValueKey('qrPayPermissionDialog'),
        title: Text(l10n.mediaPermissionDeniedTitle),
        content: Text(l10n.qrPayPhotoPermissionBody),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: Text(l10n.commonCancel)),
          FilledButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              gallery.openSettings();
            },
            child: Text(l10n.mediaOpenSettings),
          ),
        ],
      ),
    );

/// 按稿 S1 直接在画布上绘制（不依赖挂载的 widget 树，抽屉里随时可调）。
///
/// 版式（稿 336 宽 → 按 1080 等比放大）：白底；品牌名「TailTopia」紫色 → 二维码 220 → 金额 24 粗体 →
/// 订单号 / 有效期两行 12 号 ink2。缺的行不画。二维码四周留足静区（≥4 码元），保证别的 App 扫得出。
Future<Uint8List> renderQrPaymentImage({
  required String payload,
  String? amountText,
  String? orderLine,
  String? validLine,
}) async {
  const s = kQrPaySaveWidth / 336; // 稿坐标 → 导出像素
  final qrSide = 220 * s;
  final rec = ui.PictureRecorder();
  final canvas = Canvas(rec);

  TextPainter tp(String text, double size, FontWeight w, Color c) => TextPainter(
        text: TextSpan(
            text: text,
            style: TextStyle(fontFamily: 'Poppins', fontSize: size * s, fontWeight: w, color: c, height: 1.4)),
        textAlign: TextAlign.center,
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: kQrPaySaveWidth - 40 * s);

  final brand = tp('TailTopia', 15, FontWeight.w700, AppColors.mint);
  final amount = amountText == null ? null : tp(amountText, 24, FontWeight.w700, AppColors.ink);
  final order = orderLine == null ? null : tp(orderLine, 12, FontWeight.w400, AppColors.ink2);
  final valid = validLine == null ? null : tp(validLine, 12, FontWeight.w400, AppColors.ink2);

  var h = 22 * s + brand.height + 16 * s + qrSide;
  if (amount != null) h += 12 * s + amount.height;
  if (order != null) h += 2 * s + order.height;
  if (valid != null) h += 2 * s + valid.height;
  h += 26 * s;

  canvas.drawRect(Rect.fromLTWH(0, 0, kQrPaySaveWidth, h), Paint()..color = Colors.white);
  var y = 22 * s;
  void center(TextPainter p) {
    p.paint(canvas, Offset((kQrPaySaveWidth - p.width) / 2, y));
    y += p.height;
  }

  center(brand);
  y += 16 * s;
  final qr = QrPainter(
    data: payload,
    version: QrVersions.auto,
    gapless: true,
    eyeStyle: const QrEyeStyle(eyeShape: QrEyeShape.square, color: Colors.black),
    dataModuleStyle: const QrDataModuleStyle(dataModuleShape: QrDataModuleShape.square, color: Colors.black),
  );
  canvas.save();
  canvas.translate((kQrPaySaveWidth - qrSide) / 2, y);
  qr.paint(canvas, Size(qrSide, qrSide));
  canvas.restore();
  y += qrSide;
  if (amount != null) {
    y += 12 * s;
    center(amount);
  }
  if (order != null) {
    y += 2 * s;
    center(order);
  }
  if (valid != null) {
    y += 2 * s;
    center(valid);
  }

  final picture = rec.endRecording();
  final img = await picture.toImage(kQrPaySaveWidth.toInt(), h.ceil());
  try {
    final data = await img.toByteData(format: ui.ImageByteFormat.png);
    return data!.buffer.asUint8List();
  } finally {
    img.dispose();
    picture.dispose();
  }
}
