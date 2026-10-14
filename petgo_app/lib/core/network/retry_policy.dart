import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Provider 重试策略：HTTP 4xx（如「不存在 / 无权限 / 无档案」）是确定性结果，重试也不会变 → 立即进错误态；
/// 网络错误 / 5xx 委托 Riverpod 默认策略（指数退避，最多 10 次）。
///
/// 背景：Riverpod 3 默认对所有抛错的 provider 重试约 15 秒才落 AsyncError，
/// 404 页面因此要转圈 15 秒才显示「不存在」空态。用法：`FutureProvider(..., retry: retryExcept4xx)`。
Duration? retryExcept4xx(int retryCount, Object error) {
  if (error is DioException) {
    final status = error.response?.statusCode;
    if (status != null && status >= 400 && status < 500) return null;
  }
  return ProviderContainer.defaultRetry(retryCount, error);
}
