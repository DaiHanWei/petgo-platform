import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:petgo/app.dart';
import 'package:petgo/core/l10n/locale_controller.dart';
import 'package:petgo/core/mock/mock_config.dart';
import 'package:petgo/core/mock/mock_media.dart';
import 'package:petgo/core/storage/prefs.dart';
import 'package:petgo/features/profile/domain/profile_prompt_controller.dart';
import 'package:petgo/features/profile/domain/profile_prompt_state.dart';

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
    ],
    child: const PetGoApp(),
  ));
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
