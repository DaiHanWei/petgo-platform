import 'ops_target.dart';

/// 运营弹窗频次（对应后端 `OpsPopupFrequency`，wire 值区分大小写）。
enum OpsPopupFrequency {
  once('ONCE'),
  daily('DAILY');

  const OpsPopupFrequency(this.wire);

  final String wire;

  static OpsPopupFrequency? fromWire(Object? raw) {
    if (raw is! String) return null;
    for (final f in OpsPopupFrequency.values) {
      if (f.wire == raw) return f;
    }
    return null;
  }
}

/// 候选弹窗一条（V1.3.4 Story 5.4 · AC4；字段同后端 `OpsPopupCandidateView`，Story 5.1 AC3.5）。
///
/// 🔴 [token] = `public_token`（D-24）：本地频次键、曝光上报路径、埋点 `popup_id` 都只用它。
class OpsPopupCandidate {
  const OpsPopupCandidate({
    required this.token,
    required this.frequency,
    required this.imageUrlId,
    this.imageUrlEn,
    this.titleId,
    this.titleEn,
    this.bodyId,
    this.bodyEn,
    required this.buttonTextId,
    this.buttonTextEn,
    this.target = OpsTarget.none,
  });

  final String token;
  final OpsPopupFrequency frequency;
  final String imageUrlId;
  final String? imageUrlEn;
  final String? titleId;
  final String? titleEn;
  final String? bodyId;
  final String? bodyEn;
  final String buttonTextId;
  final String? buttonTextEn;
  final OpsTarget target;

  /// 单条容错（AC4.3）：缺 `token` / `imageUrlId` / `buttonTextId`、`frequency` 非 ONCE / DAILY
  /// → null（调用方丢弃该条，不影响其他条）。绝不抛。
  static OpsPopupCandidate? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final json = Map<String, dynamic>.from(raw);
    final token = _str(json['token']);
    final imageUrlId = _str(json['imageUrlId']);
    final buttonTextId = _str(json['buttonTextId']);
    final frequency = OpsPopupFrequency.fromWire(json['frequency']);
    if (token == null || imageUrlId == null || buttonTextId == null || frequency == null) {
      return null;
    }
    final target = json['target'];
    return OpsPopupCandidate(
      token: token,
      frequency: frequency,
      imageUrlId: imageUrlId,
      imageUrlEn: _str(json['imageUrlEn']),
      titleId: _str(json['titleId']),
      titleEn: _str(json['titleEn']),
      bodyId: _str(json['bodyId']),
      bodyEn: _str(json['bodyEn']),
      buttonTextId: buttonTextId,
      buttonTextEn: _str(json['buttonTextEn']),
      target: target is Map ? OpsTarget.fromJson(Map<String, dynamic>.from(target)) : OpsTarget.none,
    );
  }

  /// 非空白字符串才算有值（空串当缺失，避免渲染空标题 / 空按钮）。
  static String? _str(Object? v) => v is String && v.trim().isNotEmpty ? v : null;

  // —— 语言（AC4.2 · PRD AC6 · D-23）：EN 缺失一律回落 ID。——
  String imageUrlFor(bool isEn) => isEn ? (imageUrlEn ?? imageUrlId) : imageUrlId;
  String? titleFor(bool isEn) => isEn ? (titleEn ?? titleId) : titleId;
  String? bodyFor(bool isEn) => isEn ? (bodyEn ?? bodyId) : bodyId;
  String buttonTextFor(bool isEn) => isEn ? (buttonTextEn ?? buttonTextId) : buttonTextId;
}
