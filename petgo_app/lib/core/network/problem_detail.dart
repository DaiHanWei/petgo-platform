import 'package:dio/dio.dart';

/// RFC 9457 ProblemDetail 客户端模型（解析后端统一错误信封）。
///
/// 字段：type/title/status/detail/instance/traceId（校验错误附 errors）。
class ProblemDetail {
  const ProblemDetail({
    this.type,
    this.title,
    this.status,
    this.detail,
    this.instance,
    this.traceId,
    this.errors = const [],
    this.extensions = const {},
  });

  final String? type;
  final String? title;
  final int? status;
  final String? detail;
  final String? instance;
  final String? traceId;
  final List<FieldError> errors;

  /// RFC 9457 扩展成员（标准字段之外的顶层键，如 409 `pet-read-only` 的 `petId`）。
  /// V1.4.0 multi-pet Story 2.4 追加；默认空 map，既有调用方零影响。**只取数字 / 布尔 / token，不渲染显示串**。
  final Map<String, dynamic> extensions;

  /// type 末段（如 .../validation → "validation"），便于按语义本地化文案。
  String? get typeSlug {
    final t = type;
    if (t == null || t.isEmpty) return null;
    final seg = t.split('/');
    return seg.isEmpty ? null : seg.last;
  }

  static ProblemDetail? fromJson(Object? body) {
    if (body is! Map) return null;
    final map = body.cast<String, dynamic>();
    final rawErrors = map['errors'];
    final errors = <FieldError>[];
    if (rawErrors is List) {
      for (final e in rawErrors) {
        if (e is Map) {
          errors.add(FieldError(
            field: e['field']?.toString(),
            message: e['message']?.toString(),
          ));
        }
      }
    }
    return ProblemDetail(
      type: map['type']?.toString(),
      title: map['title']?.toString(),
      status: map['status'] is int ? map['status'] as int : int.tryParse('${map['status']}'),
      detail: map['detail']?.toString(),
      instance: map['instance']?.toString(),
      traceId: map['traceId']?.toString(),
      errors: errors,
      extensions: {
        for (final e in map.entries)
          if (!_standardKeys.contains(e.key)) e.key: e.value,
      },
    );
  }

  static const Set<String> _standardKeys = {
    'type', 'title', 'status', 'detail', 'instance', 'traceId', 'errors',
  };

  /// 从 dio 异常中尽力解析 ProblemDetail。
  static ProblemDetail? fromDioException(DioException e) {
    final pd = fromJson(e.response?.data);
    if (pd != null) return pd;
    final status = e.response?.statusCode;
    return status == null ? null : ProblemDetail(status: status);
  }
}

class FieldError {
  const FieldError({this.field, this.message});

  final String? field;
  final String? message;
}
