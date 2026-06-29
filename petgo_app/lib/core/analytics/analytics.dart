import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:posthog_flutter/posthog_flutter.dart';

/// 前端行为分析门面（PostHog Cloud US）。
///
/// 设计约束（CLAUDE.md 护栏）：
/// - Project Token / Host 走 dart-define 注入，带生产默认值，对齐 `dio_client.dart`。
/// - **绝不**向 PostHog 传 PII（email/昵称/姓名/电话）或健康数据；identify 只传哈希 distinctId，
///   既避明文 PII，又避「自增 id 直接外露」护栏。
/// - 所有上报调用 try/catch 吞错，分析失败绝不阻断主流程。
///
/// 本期为基建层：提供 init / identify / reset / 脱敏 capture，不批量埋业务事件。
class Analytics {
  Analytics._();

  /// write-only Project Token（可安全入端）。dart-define `POSTHOG_KEY` 覆盖，默认生产值。
  static const String _apiKey = String.fromEnvironment(
    'POSTHOG_KEY',
    defaultValue: 'phc_mw2Qxs3pXeHkcyyd4ahjAXUUh6aruzMxLfcFmg8ePzC',
  );

  /// 数据节点。dart-define `POSTHOG_HOST` 覆盖，默认 EU Cloud（project 211847）。
  static const String _host = String.fromEnvironment(
    'POSTHOG_HOST',
    defaultValue: 'https://eu.i.posthog.com',
  );

  /// 误传也不出端的敏感键黑名单：PII / 健康数据 / 凭证 / 精确位置 兜底剥离。
  /// 比对前对键做归一化（小写 + 去 `_`/`-`），故 `display_name`/`displayName` 等同命中。
  static const Set<String> _piiKeys = {
    // 身份 / 联系方式
    'email', 'mail', 'name', 'nickname', 'displayname', 'fullname',
    'firstname', 'lastname', 'username', 'phone', 'mobile', 'tel',
    'whatsapp', 'address', 'avatarurl', 'dob', 'birthday',
    // 健康数据
    'symptom', 'symptoms', 'diagnosis', 'medication', 'disease', 'breed',
    // 凭证 / 精确位置
    'password', 'token', 'jwt', 'lat', 'lng', 'latitude', 'longitude',
    'geo', 'ip',
  };

  /// `runApp` 前调用一次。初始化失败不抛（分析非关键路径）。
  static Future<void> init() async {
    try {
      final config = PostHogConfig(_apiKey)
        ..host = _host
        ..debug = kDebugMode // release 自动关，避免日志泄露
        ..captureApplicationLifecycleEvents = true
        ..sessionReplay = false
        // debug 下每条即时上送，便于本地/控制台实时验收；release 保持默认批量(20)。
        ..flushAt = kDebugMode ? 1 : 20;
      // 限时：setup 卡住（弱网/原生异常）不得阻塞 runApp 拖慢首帧。
      await Posthog().setup(config).timeout(const Duration(seconds: 3));
    } catch (e) {
      debugPrint('[Analytics] init failed/timeout: $e');
    }
  }

  /// 登录成功后关联用户。distinctId 取 `sha256('tailtopia-user-' + id)`，不传任何 userProperties。
  static Future<void> identifyUser(int userId) async {
    try {
      await Posthog().identify(userId: distinctIdFor(userId));
    } catch (e) {
      debugPrint('[Analytics] identify failed: $e');
    }
  }

  /// 登出 / 续期失败 → 解除关联，回到匿名。
  static Future<void> reset() async {
    try {
      await Posthog().reset();
    } catch (e) {
      debugPrint('[Analytics] reset failed: $e');
    }
  }

  /// 自定义事件上报。properties 先经 [scrub] 剥离敏感键再上报。
  static Future<void> capture(String event, [Map<String, Object>? properties]) async {
    try {
      await Posthog().capture(
        eventName: event,
        properties: properties == null ? null : scrub(properties),
      );
    } catch (e) {
      debugPrint('[Analytics] capture failed: $e');
    }
  }

  /// autocapture 点击上报：统一事件 `button_tapped`，带 `button_name`(已脱敏) + `autocaptured`。
  /// 当前屏幕由 SDK 自动以 `$screen_name` 注入（无需手动带），故此处不再附 screen。
  static Future<void> captureTap(String rawLabel) => capture('button_tapped', {
        'button_name': sanitizeTapLabel(rawLabel),
        'autocaptured': true,
      });

  /// autocapture 标签脱敏（纯函数，L0 可测）。控件标签可能是用户名/宠物名/症状等自由文本——
  /// 命中疑似 PII/自由文本（过长 / 含 `@` / 长数字串）一律替换为占位，空标签记 `(unlabeled)`。
  static String sanitizeTapLabel(String raw) {
    final s = raw.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (s.isEmpty) return '(unlabeled)';
    if (s.length > 40 || s.contains('@') || RegExp(r'\d{6,}').hasMatch(s)) {
      return '(redacted)';
    }
    return s;
  }

  /// 稳定、非明文的用户标识（送 PostHog 的 distinctId）。纯函数，L0 可测。
  ///
  /// 注：这是无盐 sha256，可被持 PostHog 读权限者暴力反推回内部自增 id；但该 id 本身
  /// 既非 PII 也非健康数据，且 distinctId 不是对外 API 面（无 IDOR 枚举风险）。V1 接受；
  /// 若日后需真正不可枚举，应由后端下发不透明分析 token（见 deferred-work）。
  static String distinctIdFor(int userId) =>
      sha256.convert(utf8.encode('tailtopia-user-$userId')).toString();

  /// 防御性剥离敏感键，返回新 map。键归一化后比对黑名单，并**递归**嵌套 map。纯函数，L0 可测。
  static Map<String, Object> scrub(Map<String, Object> props) {
    final out = <String, Object>{};
    props.forEach((k, v) {
      if (_isPiiKey(k)) return;
      if (v is Map) {
        final nested = <String, Object>{};
        v.forEach((nk, nv) {
          if (nv != null) nested[nk.toString()] = nv as Object;
        });
        out[k] = scrub(nested);
      } else {
        out[k] = v;
      }
    });
    return out;
  }

  /// 键归一化（小写 + 去非字母数字）后比对黑名单：snake_case/kebab 与 camelCase 同名一并命中。
  static bool _isPiiKey(String key) =>
      _piiKeys.contains(key.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]'), ''));
}
