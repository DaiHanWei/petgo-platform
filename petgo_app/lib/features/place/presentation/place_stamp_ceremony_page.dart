import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../data/stamp_face_loader.dart';
import '../domain/place_summary.dart';
import 'place_checkin_success_page.dart';
import 'widgets/stamp_ceremony.dart';

/// 动画页交给盖章组件的一次播放请求（同一页面实例只造一个，便于测试按身份识别）。
class StampCeremonyRequest {
  const StampCeremonyRequest({
    required this.stampPng,
    required this.reduceMotion,
    required this.onImpact,
    required this.onComplete,
    required this.onError,
  });

  final Uint8List stampPng;
  final bool reduceMotion;
  final VoidCallback onImpact;
  final VoidCallback onComplete;
  final void Function(Object error) onError;
}

/// 盖章组件工厂：默认真 [StampCeremony]；widget 测试注入假组件（测试环境没有 Rive 原生库）。
typedef StampCeremonyBuilder = Widget Function(BuildContext context, StampCeremonyRequest request);

/// 章面字节加载（默认 [loadStampFace]；测试注入）。
typedef StampFaceLoader = Future<Uint8List?> Function({String? url, PlaceType? type});

/// 新章签到后的全屏盖章动画页（V1.3.4 签到盖章动画 · spec §2）。
///
/// 流程：取章面字节（2s 超时，取不到 → 不播）→ 播 [StampCeremony] → 收到 `complete` 停 400ms →
/// `pushReplacement` 成功页（`ceremonyPlayed: true`）：成功页路由自带 300ms 淡入（见
/// [PlaceCheckinSuccessPage.pageFor]），本页最终帧垫在下面被淡入覆盖；成功页跳过原小落章。
/// 任何失败（章面取不到 / Rive 失败 / 开播 4s 未完成）→ 立即 `pushReplacement` 成功页（`ceremonyPlayed: false`）。
///
/// - 返回栈：本页被成功页**替换**，成功页返回 → 场所详情。
/// - 播放中点击无效、系统返回键无效（`PopScope(canPop: false)`）。
/// - 只导航一次：complete / 看门狗 / error 三路竞争由 [_finishing] 收口，后到的一律忽略。
/// - 切后台：组件状态保留不重播；事件丢了也有看门狗兜底，最终进成功页一次。
class PlaceStampCeremonyPage extends StatefulWidget {
  const PlaceStampCeremonyPage({super.key, required this.args, this.ceremonyBuilder, this.faceLoader});

  final PlaceCheckinSuccessArgs args;
  final StampCeremonyBuilder? ceremonyBuilder;
  final StampFaceLoader? faceLoader;

  /// 路由模板（顶层 GoRoute，与成功页同级）。⚠️ 与 `app_router.dart` 同源。
  static const String routePattern = '/places/:token/checkin-stamp';

  static String routeFor(String token) => '/places/$token/checkin-stamp';

  /// `complete` 后最终帧停留。
  static const Duration hold = Duration(milliseconds: 400);

  /// 开播后多久没收到 `complete` 就强制结束。
  ///
  /// 起算点 = 章面就绪、把播放请求交给盖章组件的那一刻（组件随后才加载 .riv 并触发 start），
  /// 即 4s 覆盖「.riv 加载 + ~2.4s 播放」。真机加载耗时待 L2 观察，偏紧再调。
  static const Duration watchdog = Duration(seconds: 4);

  @override
  State<PlaceStampCeremonyPage> createState() => _PlaceStampCeremonyPageState();
}

class _PlaceStampCeremonyPageState extends State<PlaceStampCeremonyPage> {
  StampCeremonyRequest? _request;
  Timer? _watchdog;
  Timer? _holdTimer;

  /// 已进入收尾（complete 停留中 / 已决定失败）：之后的任何回调都忽略。
  bool _finishing = false;

  /// 已发起导航：保证只 `pushReplacement` 一次。
  bool _navigated = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _prepare());
  }

  Future<void> _prepare() async {
    if (!mounted) return;
    final r = widget.args.result;
    final load = widget.faceLoader ?? loadStampFace;
    Uint8List? face;
    try {
      face = await load(url: r.stampImageUrl, type: r.placeType);
    } catch (_) {
      face = null;
    }
    if (!mounted || _finishing) return;
    if (face == null) {
      _fail('failed');
      return;
    }
    // 先读系统「减少动态效果」，随请求交给组件（为真时组件触发 skip、直接最终帧）。
    final reduceMotion = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    setState(() {
      _request = StampCeremonyRequest(
        stampPng: face!,
        reduceMotion: reduceMotion,
        onImpact: _onImpact,
        onComplete: _onComplete,
        onError: _onError,
      );
    });
    _watchdog = Timer(PlaceStampCeremonyPage.watchdog, () => _fail('timeout'));
  }

  void _onImpact() {
    if (_finishing) return;
    unawaited(HapticFeedback.mediumImpact());
  }

  void _onComplete() {
    if (_finishing || !mounted) return;
    _finishing = true;
    _watchdog?.cancel();
    _track(_request?.reduceMotion == true ? 'reduce_motion' : 'completed');
    // 停留按墙钟 Timer 计时（不依赖帧 / ticker）：切后台也不会卡在本页。淡入由成功页路由转场负责。
    _holdTimer = Timer(PlaceStampCeremonyPage.hold, () => _goToSuccess(ceremonyPlayed: true));
  }

  void _onError(Object error) {
    if (_finishing) return;
    debugPrint('PlaceStampCeremonyPage: ceremony failed: $error');
    _fail('failed');
  }

  void _fail(String result) {
    if (_finishing || !mounted) return;
    _finishing = true;
    _watchdog?.cancel();
    _track(result);
    _goToSuccess(ceremonyPlayed: false);
  }

  void _track(String result) {
    unawaited(Analytics.capture('place_stamp_ceremony_played', {'result': result}));
  }

  void _goToSuccess({required bool ceremonyPlayed}) {
    if (_navigated || !mounted) return;
    _navigated = true;
    final r = widget.args.result;
    final token = r.placeToken.isEmpty ? (GoRouterState.of(context).pathParameters['token'] ?? '') : r.placeToken;
    context.pushReplacement(
      PlaceCheckinSuccessPage.routeFor(token),
      extra: PlaceCheckinSuccessArgs(
        result: r,
        petName: widget.args.petName,
        ceremonyPlayed: ceremonyPlayed,
      ),
    );
  }

  @override
  void dispose() {
    _watchdog?.cancel();
    _holdTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final request = _request;
    return PopScope(
      canPop: false,
      child: Scaffold(
        backgroundColor: AppColors.cream,
        body: AbsorbPointer(
          key: const ValueKey('placeStampCeremonyBlocker'),
          child: SizedBox.expand(
            child: request == null ? null : (widget.ceremonyBuilder ?? _defaultCeremony)(context, request),
          ),
        ),
      ),
    );
  }

  static Widget _defaultCeremony(BuildContext context, StampCeremonyRequest request) => StampCeremony(
        stampPng: request.stampPng,
        reduceMotion: request.reduceMotion,
        onImpact: request.onImpact,
        onComplete: request.onComplete,
        onError: request.onError,
      );
}
