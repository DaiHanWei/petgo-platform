import 'package:dio/dio.dart';

import '../storage/secure_storage.dart';
import '../../features/auth/data/auth_repository.dart';

/// dio 拦截器（Story 1.3）。
///
/// - 请求：注入 `Authorization: Bearer <access>` + `Accept-Language`（驱动后端 id/en）。
///   写端点预留 `Idempotency-Key` 结构（本 Story 仅占位）。
/// - 响应错误 401：用 refresh **静默续期一次**并重放原请求；只续期一次（重入锁 + retried 标志防死循环）；
///   续期失败 → 清 token、落游客态（[onSessionExpired]，弹引导在 Story 1.5 接）。
class AuthInterceptor extends Interceptor {
  AuthInterceptor({
    required this.dio,
    required this.tokenStore,
    required this.refresh,
    required this.onSessionExpired,
    this.localeCode = _defaultLocale,
  });

  /// 用于重放原请求的 dio（通常与挂载本拦截器的同一实例）。
  final Dio dio;
  final TokenStore tokenStore;

  /// 执行一次 refresh 轮换，成功落盘新令牌并返回 true。
  final Future<bool> Function() refresh;

  /// 续期彻底失败的回调（落游客态）。
  final void Function() onSessionExpired;

  /// 当前语言码提供者（默认 en）。
  final String Function() localeCode;

  static String _defaultLocale() => 'en';

  /// 单飞刷新：并发 401 复用同一次刷新，避免重复续期/多次落态。
  Future<bool>? _refreshing;

  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final access = await tokenStore.readAccess();
    if (access != null && !options.headers.containsKey('Authorization')) {
      options.headers['Authorization'] = 'Bearer $access';
    }
    options.headers['Accept-Language'] = localeCode();
    handler.next(options);
  }

  @override
  Future<void> onError(DioException err, ErrorInterceptorHandler handler) async {
    final req = err.requestOptions;
    final bool is401 = err.response?.statusCode == 401;
    final bool skip = req.extra[AuthExtraKeys.skipRefresh] == true;
    final bool alreadyRetried = req.extra[AuthExtraKeys.retried] == true;

    if (!is401 || skip || alreadyRetried) {
      handler.next(err);
      return;
    }

    final bool ok = await _refreshOnce();
    if (!ok) {
      await tokenStore.clear();
      onSessionExpired();
      handler.next(err);
      return;
    }

    // 续期成功：带新令牌重放一次（标记 retried 防再次进入刷新）。
    try {
      final access = await tokenStore.readAccess();
      final Options options = Options(
        method: req.method,
        headers: {...req.headers, if (access != null) 'Authorization': 'Bearer $access'},
        responseType: req.responseType,
        contentType: req.contentType,
        extra: {...req.extra, AuthExtraKeys.retried: true},
      );
      final response = await dio.request<dynamic>(
        req.path,
        data: req.data,
        queryParameters: req.queryParameters,
        options: options,
      );
      handler.resolve(response);
    } on DioException catch (e) {
      handler.next(e);
    }
  }

  Future<bool> _refreshOnce() {
    final inflight = _refreshing;
    if (inflight != null) return inflight;
    final future = refresh().whenComplete(() => _refreshing = null);
    _refreshing = future;
    return future;
  }
}
