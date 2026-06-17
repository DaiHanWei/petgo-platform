import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/core/mock/mock_config.dart';
import 'package:tailtopia/core/mock/mock_media.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/domain/profile_prompt_controller.dart';
import 'package:tailtopia/features/profile/domain/profile_prompt_state.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
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
      localeOverrideProvider.overrideWithValue(savedLocale),
      // Mock 模式:覆盖上传用例(唯一不走 dio 的 OSS 直传 → 占位 URL)。其余靠 Dio MockInterceptor。
      if (kMockMode) mediaUploadUseCaseMockOverride,
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
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(
          nickname: 'Aurel',
          email: 'aurel@tailtopia.id',
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          onboardingCompleted: true,
        ),
      );
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
