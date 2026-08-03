import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:posthog_flutter/posthog_flutter.dart';

import 'appsflyer_client.dart';
import 'button_ids.dart';

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

  /// stag 分支专属总开关（2026-08-03 用户指令）：stag 环境不向 PostHog 上报任何数据。
  /// 默认关闭 = 跳过 SDK setup 且所有 Posthog() 调用短路（AppsFlyer 归因不受影响）；
  /// 如需临时验证埋点可 `--dart-define=POSTHOG_ENABLED=true` 打开。
  /// ⚠️ 本改动只留在 stag 分支，勿合回 v1.1-dev / main。
  static const bool _posthogEnabled = bool.fromEnvironment('POSTHOG_ENABLED');

  /// write-only Project Token（可安全入端）。dart-define `POSTHOG_KEY` 覆盖，默认生产值。
  static const String _apiKey = String.fromEnvironment(
    'POSTHOG_KEY',
    defaultValue: 'phc_mww2QxsJpXeHkcyyd4ahjAXUUh6aruzMxLfcFmg8ePzC',
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

  /// 自由文本字段黑名单（埋点治理 P0 兜底）：这类键的值来自 UI/用户输入，整键丢弃，
  /// 防止未来有人再引入 `button_name` 式的自由文本属性。键归一化后比对（同 [_isPiiKey]）。
  static const Set<String> _freeTextKeys = {
    'buttonname', 'title', 'label', 'text', 'content', 'caption',
  };

  /// 字符串属性值长度上限：超过大概率是用户内容（正文/病例/名字拼接），整键丢弃。
  static const int _maxStringValueLen = 64;

  /// `runApp` 前调用一次。初始化失败不抛（分析非关键路径）。
  static Future<void> init() async {
    if (!_posthogEnabled) {
      debugPrint('[Analytics] PostHog disabled (stag), skip setup');
      return;
    }
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
  /// 同一哈希值同时设为 AppsFlyer CUID（跨端归因 P0）：两端跑同一份 Dart 代码，
  /// 天然逐字节一致；不用邮箱/手机号（PDP 合规红线）。
  static Future<void> identifyUser(int userId) async {
    AppsFlyerClient.instance.setUserId(distinctIdFor(userId));
    if (!_posthogEnabled) return;
    try {
      await Posthog().identify(userId: distinctIdFor(userId));
    } catch (e) {
      debugPrint('[Analytics] identify failed: $e');
    }
  }

  /// 登出 / 续期失败 → 解除关联，回到匿名。AppsFlyer CUID 同步清空（防换账号串数据）。
  static Future<void> reset() async {
    AppsFlyerClient.instance.clearUserId();
    if (!_posthogEnabled) return;
    try {
      await Posthog().reset();
    } catch (e) {
      debugPrint('[Analytics] reset failed: $e');
    }
  }

  /// 需要同步分发给 AppsFlyer 的事件白名单（仅归因/投放相关，非 PostHog 全量复制）。
  /// `af_` 前缀 = AppsFlyer 预定义名（TikTok/Meta/Google 渠道可识别用于投放优化），
  /// 其余为自定义名（仅内部分析）。新增事件先过合规评估再登记（交付文档 §4）。
  static const Set<String> appsflyerEvents = {
    'af_complete_registration', // 注册完成（P0）
    'af_purchase', // PawCoin 充值成功——唯一真实收入事件；PawCoin 消耗**不得**计入（P0）
    'af_initiated_checkout', // 充值下单未支付（P1）
    'pet_profile_create_submitted', // 建档（P1，对应文档 profile_create）
    'triage_submitted', // 自查提交（P1）
    'consult_started', // 问诊开始（P1）
  };

  /// 事件是否分发 AppsFlyer（纯函数，L0 可测）。
  static bool isAppsFlyerEvent(String event) => appsflyerEvents.contains(event);

  /// 自定义事件上报。properties 先经 [scrub] 剥离敏感键再上报；
  /// 白名单内事件用**同一份净化后属性**同步分发 AppsFlyer（单通路，无旁路）。
  static Future<void> capture(String event, [Map<String, Object>? properties]) async {
    final clean = properties == null ? null : scrub(properties);
    if (isAppsFlyerEvent(event)) {
      AppsFlyerClient.instance.logEvent(event, clean);
    }
    if (!_posthogEnabled) return;
    try {
      await Posthog().capture(eventName: event, properties: clean);
    } catch (e) {
      debugPrint('[Analytics] capture failed: $e');
    }
  }

  /// 白名单：可上报的按钮 id 全集（埋点治理 P0）。与 [ButtonId] 常量一一对应，
  /// 新增按钮先登记 [ButtonId] 再加入此表。
  static const Set<String> _allowedButtonIds = {
    ButtonId.triageStart, ButtonId.triageUpload, ButtonId.consultStart,
    ButtonId.publishSubmit, ButtonId.profileCreate, ButtonId.milestoneShare,
    ButtonId.vetAcceptQueue, ButtonId.vetAdviceTemplate,
  };

  /// 按钮 id 是否已登记（纯函数，L0 可测）。
  static bool isRegisteredButtonId(String id) => _allowedButtonIds.contains(id);

  /// 唯一的按钮上报入口（埋点治理 P0）：id 必须是 [ButtonId] 常量。未登记 id 在 debug
  /// 断言失败、release 静默丢弃——绝不回退到从 UI 文本推导标签的旧路径。
  /// 字段用 `button_id`（受控枚举），与治理前的自由文本 `button_name` 彻底分离，
  /// 新旧数据不混在同一属性里。当前屏幕由 SDK 以 `$screen_name` 注入，[screen] 仅按需覆写。
  static Future<void> buttonTapped(String id, {String? screen}) {
    assert(isRegisteredButtonId(id), 'Unregistered button id: $id');
    if (!isRegisteredButtonId(id)) return Future.value();
    return capture('button_tapped', {
      'button_id': id,
      'screen': ?screen,
    });
  }

  /// 稳定、非明文的用户标识（送 PostHog 的 distinctId）。纯函数，L0 可测。
  ///
  /// 注：这是无盐 sha256，可被持 PostHog 读权限者暴力反推回内部自增 id；但该 id 本身
  /// 既非 PII 也非健康数据，且 distinctId 不是对外 API 面（无 IDOR 枚举风险）。V1 接受；
  /// 若日后需真正不可枚举，应由后端下发不透明分析 token（见 deferred-work）。
  static String distinctIdFor(int userId) =>
      sha256.convert(utf8.encode('tailtopia-user-$userId')).toString();

  /// 防御性剥离敏感键，返回新 map。三道规则（键归一化后比对，**递归**嵌套 map）：
  /// PII/健康键丢弃、自由文本键丢弃、超长字符串值丢弃。纯函数，L0 可测。
  static Map<String, Object> scrub(Map<String, Object> props) {
    final out = <String, Object>{};
    props.forEach((k, v) {
      if (_isPiiKey(k) || _isFreeTextKey(k)) return;
      if (v is String && v.length > _maxStringValueLen) return;
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
  static bool _isPiiKey(String key) => _piiKeys.contains(_normalizeKey(key));

  static bool _isFreeTextKey(String key) => _freeTextKeys.contains(_normalizeKey(key));

  static String _normalizeKey(String key) =>
      key.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]'), '');
}
