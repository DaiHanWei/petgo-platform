import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/data/apple_auth_client.dart';
import '../../features/auth/data/auth_repository.dart';
import '../../features/auth/data/google_auth_client.dart';
import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/domain/login_guide_controller.dart';
import '../l10n/locale_controller.dart';
import '../router/app_router.dart';
import '../storage/secure_storage.dart';
import 'api_log_interceptor.dart';
import 'auth_interceptor.dart';

/// 后端基址（运行时配置；env/--dart-define 注入，默认线上正式服）。
/// 默认值为用户明确指令固定：非用户明确要求，不得改动此默认值。
const String kApiBaseUrl =
    String.fromEnvironment('PETGO_API_BASE_URL', defaultValue: 'https://api.tailtopia.id');

/// Google OAuth serverClientId（web client；公开值/非密钥，与 iOS Info.plist 的 GIDServerClientID 同一串）。
/// 默认硬编码生产 web client：确保任何构建（含同事 pull 后漏传 dart-define 的 Android 包）拿到的
/// idToken 的 aud 恒为 web client → 后端校验得过。可经 --dart-define=GOOGLE_SERVER_CLIENT_ID 覆盖。
/// 注意：本项目无 google-services.json / strings.xml，"留空"不会回落任何配置——必须有此默认值。
const String _kGoogleServerClientId = String.fromEnvironment(
  'GOOGLE_SERVER_CLIENT_ID',
  defaultValue: '952015467016-3q9vb0ro18fnecl9gpnrddbfj9snqer0.apps.googleusercontent.com',
);

final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());

final googleAuthClientProvider = Provider<GoogleAuthClient>((ref) => GoogleSignInAuthClient(
      serverClientId: _kGoogleServerClientId.isEmpty ? null : _kGoogleServerClientId,
    ));

/// Apple 登录客户端（FR-44）。按钮仅 iOS 显示；非 iOS 平台不会触发授权。
final appleAuthClientProvider =
    Provider<AppleAuthClient>((ref) => SignInWithAppleClient());

/// 应用 Dio：基址 + 鉴权拦截器（注入 JWT / Accept-Language / 401 续期一次重放）。
final Provider<Dio> dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: kApiBaseUrl,
    connectTimeout: const Duration(seconds: 15),
    receiveTimeout: const Duration(seconds: 30),
  ));
  dio.interceptors.add(AuthInterceptor(
    dio: dio,
    tokenStore: ref.read(tokenStoreProvider),
    // 懒调用：运行期才读 authRepository，避免 provider 构造期循环依赖。
    refresh: () => ref.read(authRepositoryProvider).refresh(),
    onSessionExpired: () {
      // 续期彻底失败：落游客态 + 进登录引导流（Story 1.5 F3，非静默吞掉）。
      ref.read(authControllerProvider.notifier).toGuest();
      final ctx = rootNavigatorKey.currentContext;
      if (ctx != null) {
        // 并发 401 由 LoginGuideController 单例守卫，不叠多窗。
        ref.read(loginGuideControllerProvider).showHardDialog(ctx);
      }
    },
    localeCode: () {
      // 先取 App 内生效语言（用户在设置里手选的 id/en）；为空（跟随设备）才回落设备语言。
      // 旧实现只读设备语言 → App 选了印尼语但系统是英语时仍发 Accept-Language: en，
      // 导致后端 response_locale 恒为 en（AI 问诊/通知等按错语言作答）。
      final selected = ref.read(localeControllerProvider)?.languageCode;
      if (selected == 'id' || selected == 'en') return selected!;
      final device = WidgetsBinding.instance.platformDispatcher.locale.languageCode;
      return device == 'id' ? 'id' : 'en';
    },
  ));
  // 接口日志（仅 debug 输出控制台，脱敏）。置于 auth 之后 → 打印的是最终带鉴权的请求与真实响应。
  if (kDebugMode) {
    dio.interceptors.add(ApiLogInterceptor());
  }
  return dio;
});

final Provider<AuthRepository> authRepositoryProvider = Provider<AuthRepository>((ref) => AuthRepository(
      dio: ref.read(dioProvider),
      tokenStore: ref.read(tokenStoreProvider),
      googleClient: ref.read(googleAuthClientProvider),
      appleClient: ref.read(appleAuthClientProvider),
    ));
