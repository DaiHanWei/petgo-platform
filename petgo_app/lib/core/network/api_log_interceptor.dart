import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

/// 接口请求/响应日志拦截器（仅 debug 输出控制台）。
///
/// 护栏（CLAUDE.md）：日志严禁记录 PII / 健康数据 / 令牌 / 签名 URL。
/// 故 body 按字段名脱敏 + 签名 URL 正则打码 + 截断；**绝不打印 Authorization 头**。
class ApiLogInterceptor extends Interceptor {
  static const int _maxBodyChars = 1500;

  /// 敏感字段名（小写匹配）：值一律打码为 ***。
  static const Set<String> _sensitiveKeys = {
    'password',
    'idtoken',
    'accesstoken',
    'refreshtoken',
    'token',
    'usersig',
    'secret',
    'secretkey',
    'signature',
    'authorization',
    'email',
    'phone',
    // 健康/症状类（消费侧问诊、AI 分诊）
    'symptomtext',
    'symptom',
    'symptoms',
    // 位置坐标（NFR-4，batch-b1 复审）：`POST /places` 的请求体带设备 GPS（表单默认用当前定位）。
    // 与下面 query 参数那一组同一批键 —— 只打码 query 不打码 body 等于只堵了一半。
    'lat',
    'lng',
    'latitude',
    'longitude',
  };

  /// 签名 URL 特征（命中即整串打码）。
  static final RegExp _signedUrl =
      RegExp(r'(Signature=|OSSAccessKeyId=|x-oss-|X-Amz-|Expires=\d)', caseSensitive: false);

  static const String _startKey = '_apiLogStart';

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    options.extra[_startKey] = DateTime.now().millisecondsSinceEpoch;
    final body = _sanitize(options.data);
    debugPrint('→ ${options.method} ${options.uri.path}${_q(options)}'
        '${body.isEmpty ? '' : '  req=$body'}');
    handler.next(options);
  }

  @override
  void onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    debugPrint('← ${response.statusCode} ${response.requestOptions.method} '
        '${response.requestOptions.uri.path} (${_ms(response.requestOptions)}ms)'
        '  resp=${_sanitize(response.data)}');
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    final code = err.response?.statusCode;
    debugPrint('✗ ${code ?? err.type.name} ${err.requestOptions.method} '
        '${err.requestOptions.uri.path} (${_ms(err.requestOptions)}ms)'
        '  resp=${_sanitize(err.response?.data)}');
    handler.next(err);
  }

  /// 敏感 query 参数名 —— 值打码，只留键名。
  ///
  /// 🔴 `lat`/`lng` 是位置坐标（V1.3.0 batch-b1 Story 1.2 的 `GET /api/v1/places`）。
  /// NFR-4 的红线是**位置坐标禁止进日志**；而上面那套请求体脱敏管不到 query string ——
  /// 它此前是原样 `debugPrint` 的。虽然只在 debug 构建打印，但 debug 控制台一样会被
  /// 录屏、被整段贴进工单。
  ///
  /// ⚠️ 加新的位置 / PII 类 query 参数时必须往这里加一行。
  /// 服务端侧的同一道口子在 `ApiAccessLoggingFilter.redactQuery`（那边是落盘日志，更要紧）。
  static const Set<String> _redactQueryKeys = {'lat', 'lng', 'latitude', 'longitude'};

  String _q(RequestOptions o) {
    final params = o.uri.queryParameters;
    if (params.isEmpty) return '';
    final parts = params.entries.map((e) =>
        '${e.key}=${_redactQueryKeys.contains(e.key.toLowerCase()) ? '***' : e.value}');
    return '?${parts.join('&')}';
  }

  String _ms(RequestOptions o) {
    final start = o.extra[_startKey];
    if (start is! int) return '?';
    return '${DateTime.now().millisecondsSinceEpoch - start}';
  }

  /// 脱敏 + 截断；非结构化体只记类型。
  String _sanitize(dynamic data) {
    if (data == null) return '';
    if (data is FormData) {
      final fields = data.fields.map((e) => e.key).toList();
      return '<multipart fields=$fields files=${data.files.length}>';
    }
    dynamic redacted;
    if (data is Map || data is List) {
      redacted = _redact(data);
    } else if (data is String) {
      redacted = _redactString(data);
    } else {
      redacted = data.toString();
    }
    var out = redacted is String ? redacted : jsonEncode(redacted);
    if (out.length > _maxBodyChars) out = '${out.substring(0, _maxBodyChars)}…(truncated)';
    return out;
  }

  dynamic _redact(dynamic node) {
    if (node is Map) {
      return node.map((k, v) => MapEntry(
            k,
            _sensitiveKeys.contains(k.toString().toLowerCase()) ? '***' : _redact(v),
          ));
    }
    if (node is List) return node.map(_redact).toList();
    if (node is String) return _redactString(node);
    return node;
  }

  String _redactString(String s) => _signedUrl.hasMatch(s) ? '<signed-url>' : s;
}
