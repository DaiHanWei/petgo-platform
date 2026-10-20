import 'package:dio/dio.dart';

import '../../../core/network/api_paths.dart';
import '../domain/growth_popup_trigger.dart';

/// 「自己完成可能得 EXP 的动作」成功后触发一次成长弹窗拉取（V1.4.0 ① Story 2.3 · AD-11）。
///
/// 只看响应、不改请求：2xx 且「方法 + 路径」命中发帖 / 评论 / 回复 / 点赞 / 打卡之一 → `growthPopupTrigger.afterAction()`
/// （协调器内延迟 1.5s + 防抖）。取消点赞的 DELETE、GET、4xx / 5xx 都不触发。挂一处，不在各页面分散加调用。
class GrowthActionInterceptor extends Interceptor {
  GrowthActionInterceptor({void Function()? onAction}) : _onAction = onAction ?? growthPopupTrigger.afterAction;

  final void Function() _onAction;

  /// 按 `ApiPaths` 常量拼出的路径正则（id / token 段为任意非斜杠）。
  static final List<RegExp> _actionPaths = [
    _re(ApiPaths.contentPosts), // 发帖
    _re('${ApiPaths.contentPosts}/{}/comments'), // 评论
    _re('${ApiPaths.base}/comments/{}/replies'), // 回复
    _re('${ApiPaths.contentPosts}/{}/like'), // 点赞
    _re('${ApiPaths.places}/{}/checkins'), // 打卡
  ];

  static RegExp _re(String template) =>
      RegExp('${template.split('{}').map(RegExp.escape).join('[^/]+')}/?\$');

  static bool isAction(String method, String path, int? status) {
    if (method.toUpperCase() != 'POST') return false;
    if (status == null || status < 200 || status >= 300) return false;
    final p = Uri.tryParse(path)?.path ?? path;
    return _actionPaths.any((r) => r.hasMatch(p));
  }

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    try {
      final o = response.requestOptions;
      if (isAction(o.method, o.uri.path, response.statusCode)) _onAction();
    } catch (_) {
      // 触发失败绝不影响业务响应。
    }
    handler.next(response);
  }
}
