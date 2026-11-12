import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';
import 'app_toast.dart';
import 'qr_payment/qr_pay_parts.dart';
import 'qr_payment/qr_pay_save.dart';

/// 通用 QRIS 二维码支付面板（AI 解锁 / 身份证HD 等现金一次性购买复用）。
///
/// 展示后端下发的 EMVCo 二维码串（本地生成，照 recharge/vet_timed_pay 范式）+ 每 3s 轮询到账。
/// [pollPaid] 抛出本异常 = 付款已不可能完成（订单被取消 / 付款窗到期）：
/// 弹层关闭并 resolve `false`，与用户主动取消同路，**不**当作到账。
class QrPaymentAborted implements Exception {
  /// 🔴 **无参 const 构造必须保留**：三个不在本次改造范围内的调用点（AI 解锁 /
  /// 高清身份证创建页 / 高清身份证详情页）以及电商侧的兜底分支都在用 `const QrPaymentAborted()`。
  const QrPaymentAborted([this.category]);

  /// 中止原因（Story 1-3，电商侧传 `PaymentFailureCategory` 的字面量）。
  ///
  /// 刻意用 `String?` 而不是某个业务枚举：本组件在 `shared/` 下，不该知道 shop 的领域类型。
  /// 调用方自己把它解回枚举。
  final String? category;
}

/// [pollPaid] 返回 true 即到账 → 关闭并 resolve `true`；用户取消 → resolve `false`（纯关闭，
/// 不调后端——这些场景 pending 可复用重复支付、不清理，下次再发起复用同 intent）。
///
/// 🔴 **返回类型恒为 `Future<bool>`**（Story 1-3 AC7）：改成枚举/记录会逼 4 个调用点全改，
/// 其中 3 个不在本次改造范围内 —— 风险 G-4「支付面板改动波及其它链路」说的就是这个。
/// 新能力一律走「可选具名参数 + 默认关闭」。
///
/// [onAborted] 仅在 [pollPaid] **抛出** [QrPaymentAborted] 时回调一次（关闭之前）。
/// 🔴 用户点面板上的取消按钮**不会**触发它 —— 「付款已不可能完成」与「我先关掉待会再付」
/// 是两件事，混在一起就会把「关个面板」显示成「订单没了」。
///
/// V1.4.1 QRIS 支付页（稿 A1–A5）：抽屉固定 60% 屏高、内部滚动、按钮吸底；
/// 加金额 [amountIdr]、倒计时（按 [expiresAt] 每秒算，同一笔单关掉再开剩余时间接着走）、
/// 可复制订单号、付款 App、付款步骤、「Simpan Kode QR」。两个新参数都可选：没传对应行不画。
/// 倒计时归零 → 抽屉自己进过期态（A4）并停轮询，只剩「Tutup」（resolve `false`，与取消同路；
/// 调用方原有的过期 Toast 照旧在关闭后弹）。
///
/// ## v1.4.2 shop Story 7.2（P-11）：只追加具名可选参数，**默认值 = 现状**
///
/// 送礼 / AI 解锁 / 身份证高清 / 一次性解锁 / 月租调用点一个都不传，行为一丝不变
/// （`test/shared/qr_payment_sheet_defaults_test.dart` 钉住）。电商 E1–E7 传入：
/// - [title]：标题行 + 右侧 ×（key `qrPayCloseX`，语义同取消：resolve false、不触发 [onAborted]）。
/// - [amountLabel] / [coinHeldIdr]：金额标签与「+ PawCoin Rp x sudah ditahan」。
/// - [showPaidCheck] + [onPaidCheck] + [notPaidMessage]：「Saya sudah bayar」主动查单；true = 到账
///   （同轮询到账：关面板 resolve true），false / 抛错 → 面板内 toast [notPaidMessage]，面板保持。
///   与轮询共用 `_closing` 防重入：谁先确认到账，另一方结果被忽略。
/// - [hideCancel]：去掉红色「Batalkan Pembayaran」。
/// - [collapsibleGuide]：支付指引收进折叠头（默认收起）。
/// - [adaptiveHeight]：高度随内容（上限屏高 90%，超出内部滚动）；按钮与指引随内容排列。
/// - [savedToastMessage] / [onSaved]：存图成功 toast 与成功回调（埋点由调用方在回调里发）。
/// - [expiredBody]：过期蒙层正文。
/// - [codeFailed] + [onRetryCode]：以 E7「Kode QR gagal dibuat」态打开（保存禁用、无「我已付款」）；
///   [onRetryCode] 返回新 payload 即切回正常态，返回 null / 抛错保持 E7；为 null 不画重试。
///   失败态下轮询照常（到账仍能接住）。
/// - [isRetryClosed]：[onRetryCode] 抛错时判定「已不可能再出码」→ 切到无重试的 E7；null / 返回 false
///   = 保持可重试（默认）。
Future<bool> showQrPaymentSheet(
  BuildContext context, {
  required String payload,
  required Future<bool> Function() pollPaid,
  String? orderRef,
  void Function(QrPaymentAborted abort)? onAborted,
  int? amountIdr,
  DateTime? expiresAt,
  String? title,
  String? amountLabel,
  int? coinHeldIdr,
  bool showPaidCheck = false,
  Future<bool> Function()? onPaidCheck,
  String? notPaidMessage,
  bool hideCancel = false,
  bool collapsibleGuide = false,
  bool adaptiveHeight = false,
  String? savedToastMessage,
  VoidCallback? onSaved,
  String? expiredBody,
  bool codeFailed = false,
  Future<String?> Function()? onRetryCode,
  bool Function(Object error)? isRetryClosed,
}) async {
  final bool? result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    showDragHandle: true,
    backgroundColor: AppColors.card,
    builder: (BuildContext ctx) => _QrPaymentSheet(
        payload: payload,
        pollPaid: pollPaid,
        orderRef: orderRef,
        onAborted: onAborted,
        amountIdr: amountIdr,
        expiresAt: expiresAt,
        title: title,
        amountLabel: amountLabel,
        coinHeldIdr: coinHeldIdr,
        showPaidCheck: showPaidCheck,
        onPaidCheck: onPaidCheck,
        notPaidMessage: notPaidMessage,
        hideCancel: hideCancel,
        collapsibleGuide: collapsibleGuide,
        adaptiveHeight: adaptiveHeight,
        savedToastMessage: savedToastMessage,
        onSaved: onSaved,
        expiredBody: expiredBody,
        codeFailed: codeFailed,
        onRetryCode: onRetryCode,
        isRetryClosed: isRetryClosed),
  );
  return result ?? false;
}

class _QrPaymentSheet extends StatefulWidget {
  const _QrPaymentSheet(
      {required this.payload,
      required this.pollPaid,
      this.orderRef,
      this.onAborted,
      this.amountIdr,
      this.expiresAt,
      this.title,
      this.amountLabel,
      this.coinHeldIdr,
      this.showPaidCheck = false,
      this.onPaidCheck,
      this.notPaidMessage,
      this.hideCancel = false,
      this.collapsibleGuide = false,
      this.adaptiveHeight = false,
      this.savedToastMessage,
      this.onSaved,
      this.expiredBody,
      this.codeFailed = false,
      this.onRetryCode,
      this.isRetryClosed});

  final String payload;
  final Future<bool> Function() pollPaid;

  /// 支付号（bug 326）：客服对账用，展示在二维码下方（可空）。
  final String? orderRef;

  /// 见 [showQrPaymentSheet] 的同名参数。默认 null＝行为与改动前完全一致。
  final void Function(QrPaymentAborted abort)? onAborted;

  final int? amountIdr;
  final DateTime? expiresAt;

  // ---- v1.4.2 shop Story 7.2：全部可选，默认 = 现状（见 [showQrPaymentSheet]） ----
  final String? title;
  final String? amountLabel;
  final int? coinHeldIdr;
  final bool showPaidCheck;
  final Future<bool> Function()? onPaidCheck;
  final String? notPaidMessage;
  final bool hideCancel;
  final bool collapsibleGuide;
  final bool adaptiveHeight;
  final String? savedToastMessage;
  final VoidCallback? onSaved;
  final String? expiredBody;
  final bool codeFailed;
  final Future<String?> Function()? onRetryCode;
  final bool Function(Object error)? isRetryClosed;

  @override
  State<_QrPaymentSheet> createState() => _QrPaymentSheetState();
}

class _QrPaymentSheetState extends State<_QrPaymentSheet> with WidgetsBindingObserver {
  /// 单次到账查询的上限（修复线 2026-11-12）。
  ///
  /// 🔴 用户切去银行 App 付款时本 App 进后台，部分安卓机型会限制后台联网 —— 那一刻在途的查询会一直挂到
  /// dio 的 30 秒读超时，而 [_inFlight] 期间后续轮询全部跳过：回到 App 后抽屉还停在「等待付款」，
  /// 用户只好手动关掉才发现早已到账（生产网关回调中位 0.1 秒、最慢 2.3 秒，迟的从来不是后端）。
  static const Duration pollTimeout = Duration(seconds: 8);

  Timer? _poll;
  Timer? _clock;
  bool _closing = false;
  bool _expired = false;

  /// 当前二维码串（E7 重试成功后换成新码）。
  late String _payload = widget.payload;

  /// E7 取码失败态。
  late bool _failed = widget.codeFailed;

  /// E3「我已付款」查询中。
  bool _checking = false;

  /// E7「Coba lagi」进行中。
  bool _retrying = false;

  /// E7 重试被服务端判定为不可能再出码 → 不再画「Coba lagi」。
  bool _retryClosed = false;

  Duration? get _remaining {
    final exp = widget.expiresAt;
    if (exp == null) return null;
    final d = exp.toUtc().difference(DateTime.now().toUtc());
    return d.isNegative ? Duration.zero : d;
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _tick());
    if (widget.expiresAt != null) {
      // 1 秒心跳只为刷新倒计时与判定过期（照 recharge_page 的 _displayTimer）；到账仍以轮询为准。
      _clock = Timer.periodic(const Duration(seconds: 1), (_) => _onClock());
      if (_remaining == Duration.zero) _expire();
    }
  }

  void _onClock() {
    if (!mounted || _closing) return;
    if (!_expired && _remaining == Duration.zero) {
      _expire();
    } else {
      setState(() {});
    }
  }

  void _expire() {
    _expired = true;
    _clock?.cancel();
    // 🔴 过期后**不停**查到账，只降频：最后几秒扫码付款时，网关回调可能晚于截止时刻才到；
    //    停了就会出现「钱已到账、抽屉却一直显示已过期」。查到已付照常 pop(true) 进成功态。
    _poll?.cancel();
    _poll = Timer.periodic(const Duration(seconds: 10), (_) => _tick());
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    _clock?.cancel();
    super.dispose();
  }

  /// 回到前台（付完款从银行 App 切回来）→ 立刻查一次，不等下一个 3 秒，也不被后台时挂住的那次查询挡住。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _tick(force: true);
  }

  bool _inFlight = false;

  /// 查询代次：强制重查时 +1，旧代次的结果到得再晚也只丢弃，不会把面板关错或卡住新一轮。
  int _generation = 0;

  Future<void> _tick({bool force = false}) async {
    if (_closing) return;
    if (_inFlight && !force) return; // 慢网时不让多次轮询并发堆叠
    final int gen = ++_generation;
    _inFlight = true;
    try {
      final bool paid = await widget.pollPaid().timeout(pollTimeout);
      if (!mounted || _closing || gen != _generation) return;
      if (paid) {
        _closing = true;
        _poll?.cancel();
        _clock?.cancel();
        Navigator.of(context).pop(true);
      }
    } on QrPaymentAborted catch (abort) {
      if (!mounted || _closing || gen != _generation) return;
      _closing = true;
      _poll?.cancel();
      _clock?.cancel();
      // 在 pop 之前回调：调用方据此区分「付款已不可能完成」与「用户自己关掉了面板」——
      // 两者都 resolve false，中止信号是唯一可靠的判据。
      widget.onAborted?.call(abort);
      Navigator.of(context).pop(false);
    } catch (_) {
      // 忽略单次轮询失败（含超时），下个 tick 再试。
    } finally {
      if (gen == _generation) _inFlight = false; // 旧代次结束不能放开新代次的闸
    }
  }

  /// E3 / E4：主动查一次到账。
  Future<void> _paidCheck() async {
    final check = widget.onPaidCheck;
    if (check == null || _closing || _checking) return;
    setState(() => _checking = true);
    bool paid = false;
    try {
      paid = await check();
    } catch (_) {
      paid = false; // 查询异常与未到账同一处置（E4），不另起错误文案
    }
    if (!mounted || _closing) return; // 轮询先确认了到账 → 本次结果忽略
    if (paid) {
      _closing = true;
      _poll?.cancel();
      _clock?.cancel();
      Navigator.of(context).pop(true);
      return;
    }
    setState(() => _checking = false);
    final msg = widget.notPaidMessage;
    if (msg != null) showAppToast(context, msg, extraBottom: 40);
  }

  /// E7「Coba lagi」：拿到新码即切回正常态；失败保持 E7。
  Future<void> _retryCode() async {
    final retry = widget.onRetryCode;
    if (retry == null || _closing || _retrying) return;
    setState(() => _retrying = true);
    String? next;
    bool closed = false;
    try {
      next = await retry();
    } catch (e) {
      next = null;
      closed = widget.isRetryClosed?.call(e) ?? false;
    }
    if (!mounted || _closing) return;
    setState(() {
      _retrying = false;
      if (closed) _retryClosed = true;
      if (next != null) {
        _payload = next;
        _failed = false;
      }
    });
  }

  void _cancel() {
    if (_closing) return;
    _closing = true;
    _poll?.cancel();
    _clock?.cancel();
    Navigator.of(context).pop(false);
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final summary = QrPaySummary(
      density: QrPayDensity.sheet,
      amountIdr: widget.amountIdr,
      remaining: _remaining,
      label: widget.amountLabel,
      coinHeldIdr: widget.coinHeldIdr,
    );
    final codeCard = QrPayCodeCard(
      density: QrPayDensity.sheet,
      payload: _payload,
      orderRef: widget.orderRef,
      expired: _expired,
      expiredBody: widget.expiredBody,
      failed: _failed,
      onRetry: (_failed && !_expired && !_retrying && !_retryClosed && widget.onRetryCode != null) ? _retryCode : null,
    );
    final guide = QrPayGuide(expiresAt: widget.expiresAt, collapsible: widget.collapsibleGuide);
    final showPaidCheck = widget.showPaidCheck && !_failed;
    QrPayBottomBar bar({required bool docked}) => QrPayBottomBar(
          density: QrPayDensity.sheet,
          expired: _expired,
          onClose: _cancel,
          onSave: (_closing || _payload.isEmpty || _failed)
              ? null
              : () => saveQrPaymentImage(context,
                  payload: _payload,
                  amountIdr: widget.amountIdr,
                  orderRef: widget.orderRef,
                  expiresAt: widget.expiresAt,
                  savedMessage: widget.savedToastMessage,
                  onSaved: widget.onSaved),
          onCancel: (_closing || widget.hideCancel) ? null : _cancel,
          cancelLabel: l10n.vetPayCancel,
          paidCheckLabel: showPaidCheck ? l10n.qrPayPaidCheck : null,
          onPaidCheck: (showPaidCheck && !_closing) ? _paidCheck : null,
          paidCheckBusy: _checking,
          docked: docked,
        );
    final titleRow = widget.title == null
        ? null
        : Padding(
            padding: const EdgeInsets.fromLTRB(18, 0, 6, 6),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Text(widget.title!,
                      key: const ValueKey('qrPaySheetTitle'),
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                ),
                IconButton(
                  key: const ValueKey('qrPayCloseX'),
                  tooltip: l10n.commonClose,
                  onPressed: _closing ? null : _cancel,
                  icon: const Icon(Icons.close_rounded, color: AppColors.ink2),
                ),
              ],
            ),
          );

    if (widget.adaptiveHeight) {
      // 电商 E1：高度随内容，上限屏高 90%（扣掉拖拽条），超出内部滚动；
      // 版式自上而下：金额 → 二维码 → 主/次按钮 → 折叠指引（按钮随内容排列，不吸底）。
      final double maxHeight = MediaQuery.sizeOf(context).height * 0.9 - kMinInteractiveDimension;
      return ConstrainedBox(
        key: const ValueKey('qrPayAdaptive'),
        constraints: BoxConstraints(maxHeight: maxHeight),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            ?titleRow,
            Flexible(
              child: SingleChildScrollView(
                key: const ValueKey('qrPayScroll'),
                padding: const EdgeInsets.fromLTRB(18, 0, 18, 8),
                child: SafeArea(
                  top: false,
                  minimum: const EdgeInsets.only(bottom: 6),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: <Widget>[
                      summary,
                      const SizedBox(height: 10),
                      codeCard,
                      bar(docked: false),
                      const SizedBox(height: 6),
                      guide,
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    }

    // 抽屉固定 60% 屏高（稿 A1，含顶部拖拽条），内容在内部滚动，按钮区吸底。
    // showDragHandle 的拖拽条占 kMinInteractiveDimension（48）高，在本 SizedBox 之外，这里扣掉。
    final double height = MediaQuery.sizeOf(context).height * 0.6 - kMinInteractiveDimension;
    return SizedBox(
      height: height,
      child: Column(
        children: <Widget>[
          ?titleRow,
          Expanded(
            child: SingleChildScrollView(
              key: const ValueKey('qrPayScroll'),
              padding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  summary,
                  const SizedBox(height: 10),
                  codeCard,
                  guide,
                ],
              ),
            ),
          ),
          bar(docked: true),
        ],
      ),
    );
  }
}
