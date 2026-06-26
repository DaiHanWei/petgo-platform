import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/data/auth_repository.dart';
import '../../features/auth/data/google_auth_client.dart';
import '../../features/auth/domain/auth_state.dart';
import '../../features/auth/domain/login_guide_controller.dart';
import '../router/app_router.dart';
import '../storage/secure_storage.dart';
import 'api_log_interceptor.dart';
import 'auth_interceptor.dart';

/// 后端基址（运行时配置；env/--dart-define 注入，默认线上正式服）。
/// 默认值为用户明确指令固定：非用户明确要求，不得改动此默认值。
const String kApiBaseUrl =
    String.fromEnvironment('PETGO_API_BASE_URL', defaultValue: 'https://api.tailtopia.id');

/// Google OAuth serverClientId（L2 真实登录需要；env 注入，留空走默认配置文件）。
const String _kGoogleServerClientId = String.fromEnvironment('GOOGLE_SERVER_CLIENT_ID');

final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());

final googleAuthClientProvider = Provider<GoogleAuthClient>((ref) => GoogleSignInAuthClient(
      serverClientId: _kGoogleServerClientId.isEmpty ? null : _kGoogleServerClientId,
    ));

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
      final locale = WidgetsBinding.instance.platformDispatcher.locale;
      return locale.languageCode == 'id' ? 'id' : 'en';
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
    ));
