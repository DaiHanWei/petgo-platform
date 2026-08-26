import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker_android/image_picker_android.dart';
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/appsflyer_client.dart';
import 'package:tailtopia/core/analytics/att_gate.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/notify/domain/push_permission_bootstrap.dart';
import 'package:tailtopia/features/notify/domain/push_permission_state_reporter.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Android：改用系统 Photo Picker（纯图片选择 UI）。否则默认 ACTION_GET_CONTENT 在无 GMS
  // 华为 EMUI 上会弹成文档选择器（「最近」文件）而非相册。不支持时插件自动回退，无副作用。
  final picker = ImagePickerPlatform.instance;
  if (picker is ImagePickerAndroid) {
    picker.useAndroidPhotoPicker = true;
  }
  // 本地化日期格式化的 locale 数据（id 非默认 locale，DateFormat 用前必须初始化）。
  await initializeDateFormatting();
  // 前端行为分析（PostHog）：**发起但不 await**（2026-08-07 冷启动耗时治理）。
  //
  // 改前是 `await Analytics.init()` —— 原生 setup（超时上限 3s）被压在首帧之前，那一段
  // 画面还是原生启动屏，用户在纯等待，而首帧之前没有任何东西需要 PostHog 就绪。
  //
  // 为什么不 await 也**不会丢首帧那条 `$screen`**（漏斗「过启动页」这一步的来源）：
  // `Posthog().setup()` 在本行**同步**把 setup 消息投进 `posthog_flutter` 平台通道
  // （Dart 侧从调用到 `invokeMethod` 之间没有 await），而后续 capture / screen 走**同一条
  // 通道**、原生侧按序取用且 setup 是同步处理的 ⇒「setup 先于任何上报被执行」由通道的
  // 顺序性保证，不需要 Dart 侧 await 来串。
  //
  // ⚠️ 因此本行**必须留在 `runApp` 之前**。一旦挪到首帧之后（比如并进下面的 postFrame
  // 回调），PosthogObserver 为启动屏打的那条 `$screen` 就会排在 setup 前面 —— 原生 SDK
  // 对 setup 前的事件是**直接丢弃**（不缓冲），启动漏斗的第一级会凭空消失。
  unawaited(Analytics.init());
  // V1：锁定竖屏（portrait-only）。
  SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // Story 7.2：读持久化语言选择（空/缺失 = 跟随设备）。
  final savedLocale = await _loadSavedLocale();

  runApp(ProviderScope(
    overrides: [
      // Debug-only：--dart-define=DEV_LOCALE=id 强制语言（视觉验收对齐印尼语原型）；否则跟持久化/设备。
      localeOverrideProvider.overrideWithValue(
        kDebugMode && const String.fromEnvironment('DEV_LOCALE').isNotEmpty
            ? const String.fromEnvironment('DEV_LOCALE')
            : savedLocale,
      ),
      // Debug-only：--dart-define=DEV_VET=true 启动即种子兽医登录态，配合 DEV_ROUTE 直达兽医屏做视觉验收。
      if (kDebugMode && const bool.fromEnvironment('DEV_VET'))
        authControllerProvider.overrideWith(_DevVetAuthController.new),
      // Debug-only：--dart-define=DEV_USER=true 启动即种子普通用户登录态（HAS_PET 已建档），
      // 配合 DEV_ROUTE 直达登录态用户屏（首页/成长档案/我的/咨询）做视觉验收。
      if (kDebugMode && const bool.fromEnvironment('DEV_USER'))
        authControllerProvider.overrideWith(_DevUserAuthController.new),
    ],
    child: const TailTopiaApp(),
  ));

  // 首帧后：归因 SDK 初始化 + iOS ATT 授权 + 启动上报，三件事全部让开首帧。
  //
  // 2026-08-07 冷启动耗时治理：`AppsFlyerClient.init()`（原生 initSdk，超时上限 3s）改前在
  // `runApp` 之前 await —— 首帧之前没有任何东西依赖归因 SDK 就绪，那 3s 纯粹是让用户
  // 多看几秒原生启动屏。移到这里后，最坏情况的等待从「用户盯着不动的紫屏」变成
  // 「启动屏动效照常播」，不再计入 time-to-content。
  //
  // ⚠️ 四者的先后不可随手改：
  // - init 与 ATT **并发**发起（`afInit` 先拿到 future 再 await ATT），不是串行 ——
  //   串行会让 ATT 弹窗被 initSdk 拖后最多 3s，而「等 resumed 再弹 ATT」正是 2026-08-06
  //   审核拒信（Guideline 2.1）的修复点，不能再往后推。
  // - **通知权限必须排在 ATT 之后**（2026-08-07）：两个系统弹窗同帧抛出会互相遮挡，
  //   两者授权率一起掉；串行让用户先答完跟踪、再答通知。
  // - `start()` 必须同时晚于 ATT 结果与 init（未 init 时 start 是 no-op），故末尾按序 await 两者。
  WidgetsBinding.instance.addPostFrameCallback((_) async {
    final Future<void> afInit = AppsFlyerClient.instance.init();
    final attSettled = await AttGate.requestIfNeeded();
    // 首启即申请通知权限（2026-08-07 产品决策，取代 F7 双时机——见 PushPermissionBootstrap
    // 文档：双时机对存量用户是死路）。拒绝后不再自动弹，改由设置页开关兜底。
    //
    // 🔴 必须在 ATT **落定之后**且**仅在弹窗已收场时**（PR#34 finding #14）：
    // `requestIfNeeded()` 返回 false = 用户 15s 未作答、ATT 弹窗还开着——此时再弹通知权限
    // 会盖住它 ⇒ 用户对跟踪没得选 ⇒ 重蹈 2026-08-06 Guideline 2.1 拒信。
    // 宁可把通知权限推迟到下一次冷启动（首启标记未消费，下次照常询问）。
    if (attSettled) {
      await PushPermissionBootstrap.requestOnFirstLaunch();
    } else {
      debugPrint('[ATT] undetermined after wait — defer notification prompt to next launch');
    }

    // 权限状态快照（Story 8.1 / 埋点 E-21）：本次启动的通知权限流程**落定之后**报一次。
    //
    // 🔴 位置不能提前：报「申请之前」的状态会让首启永远是 false，把授权率系统性低估。
    // 🔴 **两条分支之后都要报**（故放在 if/else 之外）：ATT 未落定那支不会走到
    //    askNotificationOnce()，只在 if 里报就会漏掉全部「ATT 未落定」的启动 ——
    //    那不是随机缺失，只缺 iPad 兼容模式 / 请求被系统吞掉这类设备，趋势会被带偏。
    // ⚠️ 上面 installForegroundRetry 的补弹回调里**不要**再报：那是同一会话内的补弹、
    //    不是新的冷启动，再报会违反「一次冷启动恰好一次」（分母会被重度用户拉偏）。
    //    该类内有进程内一次性闸，即使误调也只空转。
    // ⚠️ 本行不参与 init / ATT / 通知权限 / start 四者的排序，只在其后追加。
    await PushPermissionStateReporter.reportOnColdStart();

    await afInit;
    await AppsFlyerClient.instance.start();
  });
}

/// Debug-only：开发直达兽医屏时的预置兽医登录态（仅 `DEV_VET=true` 时注入）。
class _DevVetAuthController extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'VET');
}

/// Debug-only：开发直达登录态用户屏的预置普通用户（HAS_PET 已建档；仅 `DEV_USER=true` 时注入）。
class _DevUserAuthController extends AuthController {
  @override
  AuthState build() {
    // --dart-define=DEV_NOPET=true：HAS_PET 但未建档（截 pet-create 建档表单用，避免被重定向到已存在档案）。
    const noPet = bool.fromEnvironment('DEV_NOPET');
    return const AuthState(
      status: AuthStatus.authenticated,
      role: 'USER',
      profile: UserProfile(
        nickname: 'Aurel',
        email: 'aurel@tailtopia.id',
        petStatus: 'HAS_PET',
        hasPetProfile: !noPet,
        onboardingCompleted: true,
      ),
    );
  }
}

/// Story 7.2：读持久化语言码（'id'/'en'）；空串/缺失/损坏 → null（跟随设备）。
Future<String?> _loadSavedLocale() async {
  try {
    final code = (await AppPrefs.create()).localeCode;
    return (code == 'id' || code == 'en') ? code : null;
  } catch (_) {
    return null;
  }
}

