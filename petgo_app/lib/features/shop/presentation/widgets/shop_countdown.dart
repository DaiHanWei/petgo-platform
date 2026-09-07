import 'dart:async';

import 'package:flutter/material.dart';

import '../../../../core/theme/shop_tokens.dart';

/// 倒计时（待支付 60 分钟、促销结束）。
///
/// 🔴 <b>三条硬规则，逐条都有踩过的坑</b>：
///
/// 1. <b>到期时刻由服务端下发，前端只渲染</b>。不接受「剩余秒数」作为入参 ——
///    剩余秒数一旦被前端拿去累加，页面停留越久误差越大，最后会出现「倒计时显示还剩
///    3 分钟，点下去却提示订单已过期」。本组件只收绝对时刻 [expiresAt]。
///
/// 2. <b>切后台回来必须重算，不本地累加</b>。iOS/Android 都会在后台节流甚至冻结定时器，
///    靠 `remaining--` 的实现从后台回来会慢一大截。这里每次 tick 都用
///    `expiresAt - now` <b>重新算</b>，定时器只负责触发重绘，不持有状态。
///    另监听 [AppLifecycleState.resumed] 立即重算一次，不等下一个 tick。
///
/// 3. <b>等宽字体</b>（[ShopText.mono]）。比例字体下 `58:12 → 58:11` 会因为 1 比 2 窄
///    而让整行左右抖动，一秒抖一次。设计稿把这条单列为不可省的规则。
///
/// ⚠️ <b>已知局限</b>：`DateTime.now()` 取的是设备时钟，用户手动改系统时间会让倒计时失真。
/// 服务端在真正扣款/关单时以自己的时钟为准，所以最坏后果是「显示与结果不一致」而非资损。
/// 要彻底解决需要服务端同时下发 `serverNow` 供前端算时钟偏移 —— 当前接口没有该字段，
/// 补齐前不要自行用别的字段推算。
class ShopCountdown extends StatefulWidget {
  const ShopCountdown({
    super.key,
    required this.expiresAt,
    required this.style,
    this.onExpired,
    this.now,
  });

  /// 到期时刻（UTC）。
  final DateTime expiresAt;

  /// 文字样式。须来自 [ShopText.countdownHero] / [ShopText.countdownInline] ——
  /// 两者都已带等宽字族。
  final TextStyle style;

  /// 归零回调。页面据此切到过期态（倒计时块转灰、底部条换成单个 `Beli Lagi`）。
  ///
  /// 🔴 只会被调用**一次**。不要在这里做 `setState` 之外的重活（如发请求），
  /// 归零时刻大量订单页可能同时触发。
  final VoidCallback? onExpired;

  /// 当前时刻的来源。生产环境留 `null` 即取设备时钟。
  ///
  /// 🔴 <b>存在的唯一理由是可测性</b>：本组件最要紧的性质是「每次都用
  /// `expiresAt - now` 重算，而不是本地累加」，而 `testWidgets` 的 `pump(duration)`
  /// 推的是 fake async 时钟、<b>推不动 `DateTime.now()`</b>。没有这个注入点，
  /// 「重算」这条性质就只能写在注释里而无法被测试锁住 —— 而它一旦退化成累加，
  /// 表现是「后台待久了倒计时慢一大截」，本地几乎复现不出来。
  final DateTime Function()? now;

  @override
  State<ShopCountdown> createState() => _ShopCountdownState();
}

class _ShopCountdownState extends State<ShopCountdown> with WidgetsBindingObserver {
  Timer? _timer;
  bool _expiredNotified = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startTimer();
  }

  @override
  void didUpdateWidget(ShopCountdown old) {
    super.didUpdateWidget(old);
    if (old.expiresAt != widget.expiresAt) {
      // 换了一张订单/一个活动 → 过期通知的「只报一次」重新计数
      _expiredNotified = false;
      _startTimer();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // 🔴 立即重算，不等下一个 tick —— 否则回到前台最多会显示 1 秒的陈旧值。
      //    真正的「重算」发生在 build 里（每次都用 expiresAt - now），这里只是催一次重绘。
      _startTimer();
      if (mounted) setState(() {});
    }
  }

  void _startTimer() {
    _timer?.cancel();
    if (_remaining() > Duration.zero) {
      _timer = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        setState(() {});
        if (_remaining() <= Duration.zero) {
          _timer?.cancel();
          _notifyExpiredOnce();
        }
      });
    } else {
      _notifyExpiredOnce();
    }
  }

  void _notifyExpiredOnce() {
    if (_expiredNotified) return;
    _expiredNotified = true;
    // 归零可能发生在 build 期间（首帧就已过期），回调里通常有 setState → 延到帧后。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onExpired?.call();
    });
  }

  Duration _remaining() =>
      widget.expiresAt.difference((widget.now ?? _systemNow)());

  static DateTime _systemNow() => DateTime.now().toUtc();

  @override
  Widget build(BuildContext context) {
    return Text(
      formatCountdown(_remaining()),
      style: widget.style,
      // 🔒 屏幕阅读器逐秒播报倒计时会淹没其它内容；这里只暴露静态语义，
      //    具体剩余时间由页面上的说明文案承载。
      semanticsLabel: '',
    );
  }
}

/// 倒计时格式化。< 1 小时用 `mm:ss`，否则 `h:mm:ss`。已过期（含负数）恒返回 `00:00`。
///
/// 🔴 <b>秒数向上取整，不截断</b>。剩余 59 分 59.9 秒时截断会显示 `59:59` ——
/// 于是一个 60 分钟的支付窗口<b>刚打开就少了一分钟</b>，用户会以为自己已经耗了 1 分钟。
/// 向上取整则显示 `1:00:00`，并在真正走完 1 秒后才跳到 `59:59`，
/// 且 `00:00` 只在真正到期时出现（而不是最后 1 秒就提前归零）。
///
/// 独立成顶层函数是为了能单测边界（59:59 / 1:00:00 / 负数），不必为格式化去搭一个 widget。
String formatCountdown(Duration d) {
  if (d <= Duration.zero) return '00:00';
  final total = (d.inMilliseconds / 1000).ceil();
  final h = total ~/ 3600;
  final m = (total % 3600) ~/ 60;
  final s = total % 60;
  final mm = m.toString().padLeft(2, '0');
  final ss = s.toString().padLeft(2, '0');
  return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
}
