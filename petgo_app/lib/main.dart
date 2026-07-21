import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker_android/image_picker_android.dart';
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/domain/profile_prompt_controller.dart';
import 'package:tailtopia/features/profile/domain/profile_prompt_state.dart';

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
  // 前端行为分析（PostHog）：runApp 前初始化，失败不阻断启动。
  await Analytics.init();
  // V1：锁定竖屏（portrait-only）。
  SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // Story 1.7：加载档案提示条持久态 + 本次冷启动计数 +1（FR-0H）。
  final promptBootstrap = await _loadProfilePromptBootstrap();
  // Story 7.2：读持久化语言选择（空/缺失 = 跟随设备）。
  final savedLocale = await _loadSavedLocale();

  runApp(ProviderScope(
    overrides: [
      profilePromptBootstrapProvider.overrideWithValue(promptBootstrap),
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

Future<ProfilePromptState> _loadProfilePromptBootstrap() async {
  try {
    final prefs = await AppPrefs.create();
    var state = ProfilePromptState(
      restartCount: prefs.getInt(AppPrefs.kProfilePromptRestartCount),
      dismissedPermanently: prefs.getBool(AppPrefs.kProfilePromptDismissedPermanently),
      petProfileCompleted: prefs.getBool(AppPrefs.kPetProfileCompleted),
    );
    state = onColdStartIncrement(state); // 本次冷启动 +1
    await prefs.setInt(AppPrefs.kProfilePromptRestartCount, state.restartCount);
    return state;
  } catch (_) {
    return const ProfilePromptState(restartCount: 1); // prefs 缺失/损坏 → 默认首启
  }
}
