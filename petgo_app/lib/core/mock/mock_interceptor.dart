import 'package:dio/dio.dart';

import 'mock_backend.dart';

/// Dio Mock 拦截器：Mock 模式下短路**所有**请求,由 [MockBackend] 返回内存假数据,永不触网。
///
/// 置于 AuthInterceptor 之前 → `handler.resolve` 后链路终止,不会真正发网络请求。
class MockInterceptor extends Interceptor {
  @override
  Future<void> onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    // 模拟轻微网络延迟,使页面加载态可见、更接近真实观感。
    await Future<void>.delayed(const Duration(milliseconds: 150));
    try {
      final res = MockBackend.instance.handle(options);
      if (res != null) {
        handler.resolve(res);
        return;
      }
      handler.resolve(Response<dynamic>(requestOptions: options, statusCode: 200, data: {}));
    } on DioException catch (e) {
      // 语义化错误(如 404 无档案)按真实链路抛回,让 repository 的 catch 正常处理。
      handler.reject(e);
    }
  }
}
