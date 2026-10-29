import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../app_toast.dart';
import '../pay_channel_picker.dart' show formatIdrAmount;

/// V1.4.1 QRIS 支付页（稿 ui-1-4-1-qr-payment-integrated-v1）的共用零件。
///
/// 三处共用：通用支付抽屉 A（[showQrPaymentSheet]）、PawCoin 充值页 B、问诊计时付费页 C。
/// 抽屉与整页的差别只在尺寸（[QrPayDensity]）：抽屉 60% 屏高，二维码 168、金额 20；整页二维码 196、金额 22，
/// 金额区多一块浅紫底卡与「Batas bayar」行。
enum QrPayDensity { sheet, page }

/// 倒计时文案：mm:ss（≥1h 显示 h:mm:ss）。负值按 0。
String qrPayCountdownText(Duration d) {
  final v = d.isNegative ? Duration.zero : d;
  final h = v.inHours;
  final m = v.inMinutes % 60;
  final s = v.inSeconds % 60;
  final mm = m.toString().padLeft(2, '0');
  final ss = s.toString().padLeft(2, '0');
  return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
}

/// 到期时刻（稿「到期时刻怎么显示」）：按**手机本地时区**、24 小时制、不带时区后缀。
///
/// - 到期在今天 → `HH:mm`；跨天 → `d MMM, HH:mm`（月份按 [locale] 缩写，印尼语即 Okt）。
/// - [withYear]：保存到相册的图片会被留着以后看，固定 `d MMM yyyy, HH:mm`。
String qrPayDeadlineText(DateTime expiresAt, String locale, {DateTime? now, bool withYear = false}) {
  final local = expiresAt.toLocal();
  if (withYear) return DateFormat('d MMM yyyy, HH:mm', locale).format(local);
  final n = (now ?? DateTime.now()).toLocal();
  final sameDay = n.year == local.year && n.month == local.month && n.day == local.day;
  return sameDay
      ? DateFormat('HH:mm', locale).format(local)
      : DateFormat('d MMM, HH:mm', locale).format(local);
}

/// 「Bisa dibayar pakai」9 个付款 App（官方 logo，顺序照稿）。
const List<String> kQrPayLogos = [
  'gopay', 'dana', 'ovo', 'shopeepay', 'bca', 'bri', 'mandiri', 'bni', 'seabank',
];

/// 金额 + 倒计时（稿 `.shead` / `.sum`）。
///
/// [amountIdr] 为空则左栏不画；[remaining] 为空则右栏不画（两者都是可选能力，没传不阻塞）。
class QrPaySummary extends StatelessWidget {
  const QrPaySummary({
    super.key,
    required this.density,
    this.amountIdr,
    this.coins,
    this.remaining,
    this.expiresAt,
    this.urgent = false,
    this.countdownKey = const ValueKey('qrPayCountdown'),
  });

  final QrPayDensity density;
  final int? amountIdr;

  /// 沿用各页既有倒计时 key（充值 `rechargeCountdown`、问诊 `vetPayCountdown`）。
  final Key countdownKey;

  /// 充值到账 koin（B1「= 50.000 koin」）；null 不画。
  final int? coins;
  final Duration? remaining;

  /// 用于「Batas bayar {time}」（只在整页形态画）。
  final DateTime? expiresAt;

  /// 问诊 5 分钟窗：倒计时红色强调（稿 C1 `.cd.urg`）。
  final bool urgent;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context).languageCode;
    final page = density == QrPayDensity.page;
    final big = page ? 22.0 : 20.0;
    final expired = remaining != null && remaining! <= Duration.zero;
    const label = TextStyle(fontSize: 11.5, fontWeight: FontWeight.w500, color: AppColors.ink2);

    final left = amountIdr == null
        ? const SizedBox.shrink()
        : Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.qrPayTotalLabel, style: label),
              Text('Rp${formatIdrAmount(amountIdr!)}',
                  key: const ValueKey('qrPayAmount'),
                  style: TextStyle(
                      fontSize: big,
                      height: 1.3,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.2,
                      color: AppColors.ink,
                      fontFeatures: const [FontFeature.tabularFigures()])),
              if (page && coins != null)
                Text(l10n.qrPayCoinsSub(formatIdrAmount(coins!)),
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.mint)),
            ],
          );

    final right = remaining == null
        ? const SizedBox.shrink()
        : Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(l10n.qrPayPayWithin, style: label),
              Text(qrPayCountdownText(remaining!),
                  key: countdownKey,
                  style: TextStyle(
                      fontSize: big,
                      height: 1.3,
                      fontWeight: FontWeight.w700,
                      color: expired
                          ? AppColors.muted
                          : (urgent ? AppColors.danger : AppColors.mint),
                      fontFeatures: const [FontFeature.tabularFigures()])),
              if (expired)
                Text(l10n.qrPayExpired, style: const TextStyle(fontSize: 11, color: AppColors.muted))
              else if (page && expiresAt != null)
                Text(l10n.qrPayDeadline(qrPayDeadlineText(expiresAt!, locale)),
                    style: const TextStyle(fontSize: 11, color: AppColors.muted)),
            ],
          );

    final row = Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: page ? CrossAxisAlignment.start : CrossAxisAlignment.end,
      children: [Flexible(child: left), const SizedBox(width: 10), right],
    );
    if (!page) {
      return Padding(padding: const EdgeInsets.symmetric(horizontal: 2), child: row);
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(16)),
      child: row,
    );
  }
}

/// 二维码卡（稿 `.qrc`）：二维码 → 订单号（带复制）→ 等待中。
///
/// - [payload] 为空 → 测试环境占位框（稿 B6，[stubHint] 文案）。
/// - [expired] → 二维码蒙白 + 过期提示，等待中消失（稿 A4）。
class QrPayCodeCard extends StatelessWidget {
  const QrPayCodeCard({
    super.key,
    required this.density,
    required this.payload,
    this.orderRef,
    this.expired = false,
    this.showWaiting = true,
    this.stubHint,
    this.qrKey = const ValueKey('qrPayImage'),
    this.orderRefKey = const ValueKey('qrPayOrderRef'),
  });

  final QrPayDensity density;
  final String? payload;
  final String? orderRef;
  final bool expired;
  final bool showWaiting;
  final String? stubHint;

  /// 沿用各页既有 key（抽屉 `qrPayImage`、问诊 `vetPayQr`…），不弄断既有测试。
  final Key qrKey;
  final Key orderRefKey;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final side = density == QrPayDensity.page ? 196.0 : 168.0;
    final data = payload ?? '';
    final hasPayload = data.isNotEmpty;
    final isHttp = data.startsWith('http://') || data.startsWith('https://');

    Widget stub() => Container(
          width: side,
          height: side,
          padding: const EdgeInsets.symmetric(horizontal: 24),
          decoration: BoxDecoration(
            color: AppColors.cream2,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: AppColors.line),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.qr_code_2, size: 56, color: AppColors.mint),
              const SizedBox(height: 8),
              Text(stubHint ?? l10n.rechargeStubHint,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ],
          ),
        );

    Widget code;
    if (!hasPayload) {
      code = KeyedSubtree(key: const ValueKey('qrPayStub'), child: stub());
    } else {
      // GemPay 返回 QRIS/EMVCo 串，本地生成二维码；历史上偶有 http 图片链接，回退 Image.network。
      final Widget img = isHttp
          ? Image.network(data, key: qrKey, width: side, height: side, fit: BoxFit.contain,
              errorBuilder: (_, _, _) => stub())
          : QrImageView(
              key: qrKey,
              data: data,
              version: QrVersions.auto,
              padding: EdgeInsets.zero,
              backgroundColor: Colors.white,
              errorStateBuilder: (_, _) => stub(),
            );
      code = ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: SizedBox(
          width: side,
          height: side,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Opacity(opacity: expired ? 0.12 : 1, child: img),
              if (expired)
                Padding(
                  key: const ValueKey('qrPayExpiredOverlay'),
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(Icons.timer_off_outlined, size: 30, color: AppColors.muted),
                      const SizedBox(height: 6),
                      Text(l10n.rechargeExpiredTitle,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                              fontSize: 13.5, fontWeight: FontWeight.w700, color: AppColors.ink)),
                      const SizedBox(height: 2),
                      Text(l10n.qrPayExpiredBody,
                          textAlign: TextAlign.center,
                          style: const TextStyle(fontSize: 11.5, height: 1.4, color: AppColors.ink2)),
                    ],
                  ),
                ),
            ],
          ),
        ),
      );
    }

    final ref = orderRef ?? '';
    return Container(
      width: double.infinity,
      padding: EdgeInsets.symmetric(horizontal: 16, vertical: density == QrPayDensity.page ? 14 : 12),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.line2),
        boxShadow: const [BoxShadow(color: Color(0x0A2B2A27), offset: Offset(0, 3), blurRadius: 12)],
      ),
      child: Column(
        children: [
          code,
          if (ref.isNotEmpty) ...[
            const SizedBox(height: 10),
            InkWell(
              key: orderRefKey,
              borderRadius: BorderRadius.circular(6),
              onTap: () async {
                await Clipboard.setData(ClipboardData(text: ref));
                if (context.mounted) showAppToast(context, l10n.commonCopied, extraBottom: 40);
              },
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Flexible(
                      child: Text.rich(
                        TextSpan(children: [
                          TextSpan(text: '${l10n.orderNumberLabel}: '),
                          TextSpan(
                              text: ref,
                              style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                  color: AppColors.ink2,
                                  fontFeatures: [FontFeature.tabularFigures()])),
                        ]),
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 12, color: AppColors.muted),
                      ),
                    ),
                    const SizedBox(width: 4),
                    const Icon(Icons.copy_rounded, key: ValueKey('qrPayCopy'), size: 14, color: AppColors.mint),
                  ],
                ),
              ),
            ),
          ],
          if (showWaiting && !expired) ...[
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: AppColors.mint, backgroundColor: AppColors.violet100),
                ),
                const SizedBox(width: 8),
                Text(l10n.rechargeWaiting, style: const TextStyle(fontSize: 12.5, color: AppColors.ink2)),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// 「Bisa dibayar pakai」logo 行 +「Cara bayar」5 条步骤（稿 `.blk`）。
class QrPayGuide extends StatelessWidget {
  const QrPayGuide({super.key, this.expiresAt});

  /// 步骤 5 的有效期；null 时步骤 5 不画（没有付款窗就不承诺期限）。
  final DateTime? expiresAt;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context).languageCode;
    const title = TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink);
    final steps = <String>[
      l10n.qrPayStep1,
      l10n.qrPayStep2,
      l10n.qrPayStep3,
      l10n.qrPayStep4,
      if (expiresAt != null) l10n.qrPayStep5(qrPayDeadlineText(expiresAt!, locale)),
    ];
    return Column(
      key: const ValueKey('qrPayGuide'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 18),
        Padding(padding: const EdgeInsets.symmetric(horizontal: 2), child: Text(l10n.qrPaySupportedApps, style: title)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            for (final name in kQrPayLogos)
              Container(
                height: 30,
                padding: const EdgeInsets.symmetric(horizontal: 9),
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.card,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.line2),
                ),
                child: Image.asset('assets/payment/$name.png', height: 15, semanticLabel: name),
              ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Text(l10n.qrPayMoreApps, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ),
          ],
        ),
        const SizedBox(height: 18),
        Padding(padding: const EdgeInsets.symmetric(horizontal: 2), child: Text(l10n.qrPayHowTo, style: title)),
        const SizedBox(height: 8),
        for (var i = 0; i < steps.length; i++)
          Padding(
            padding: EdgeInsets.only(top: i == 0 ? 0 : 9),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 20,
                  height: 20,
                  margin: const EdgeInsets.only(top: 1),
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
                  child: Text('${i + 1}',
                      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.mint)),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(steps[i],
                      style: const TextStyle(fontSize: 12.5, height: 1.5, color: AppColors.ink2)),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// 吸底按钮区（稿 `.bottom`）：「Simpan Kode QR」主按钮 + 可选红色文字按钮 / 过期后只剩「Tutup」。
class QrPayBottomBar extends StatelessWidget {
  const QrPayBottomBar({
    super.key,
    required this.density,
    required this.onSave,
    this.onCancel,
    this.cancelLabel,
    this.cancelKey = const ValueKey('qrPayCancel'),
    this.expired = false,
    this.onClose,
  });

  final QrPayDensity density;

  /// null = 置灰（测试环境无二维码，稿 B6）。
  final VoidCallback? onSave;
  final VoidCallback? onCancel;
  final String? cancelLabel;
  final Key cancelKey;

  /// 过期（稿 A4）：按钮只剩「Tutup」。
  final bool expired;
  final VoidCallback? onClose;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hPad = density == QrPayDensity.sheet ? 18.0 : 16.0;
    Widget primary;
    if (expired) {
      primary = SizedBox(
        width: double.infinity,
        height: 50,
        child: OutlinedButton(
          key: const ValueKey('qrPayClose'),
          onPressed: onClose,
          style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.ink,
            side: const BorderSide(color: AppColors.line, width: 1.5),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          child: Text(l10n.commonClose, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        ),
      );
    } else {
      primary = SizedBox(
        width: double.infinity,
        height: 50,
        child: FilledButton.icon(
          key: const ValueKey('qrPaySave'),
          onPressed: onSave,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.mint,
            foregroundColor: Colors.white,
            disabledBackgroundColor: AppColors.line2,
            disabledForegroundColor: AppColors.muted,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          icon: const Icon(Icons.download_rounded, size: 18),
          label: Text(l10n.qrPaySave, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        ),
      );
    }
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.card,
        border: Border(top: BorderSide(color: AppColors.line2)),
      ),
      padding: EdgeInsets.fromLTRB(hPad, 10, hPad, 10),
      child: SafeArea(
        top: false,
        minimum: const EdgeInsets.only(bottom: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            primary,
            if (!expired && onCancel != null && cancelLabel != null)
              InkWell(
                key: cancelKey,
                onTap: onCancel,
                borderRadius: BorderRadius.circular(8),
                child: Container(
                  width: double.infinity,
                  constraints: const BoxConstraints(minHeight: 40),
                  padding: const EdgeInsets.only(top: 10),
                  alignment: Alignment.center,
                  child: Text(cancelLabel!,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.danger)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
