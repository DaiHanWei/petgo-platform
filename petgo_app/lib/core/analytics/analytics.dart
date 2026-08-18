import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:posthog_flutter/posthog_flutter.dart';

import 'appsflyer_client.dart';
import 'button_ids.dart';

/// 前端行为分析门面（PostHog Cloud EU · project 211847）。
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
    try {
      await Posthog().identify(userId: distinctIdFor(userId));
    } catch (e) {
      debugPrint('[Analytics] identify failed: $e');
    }
  }

  /// 登出 / 续期失败 → 解除关联，回到匿名。AppsFlyer CUID 同步清空（防换账号串数据）。
  static Future<void> reset() async {
    AppsFlyerClient.instance.clearUserId();
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

  /// 测试观察点（Story 6.1）：非 null 时，每条 [capture] / [screen] 都同步回调一次
  /// **净化后**的属性。埋点断言必须看到端上真正发出的形态，所以钩子挂在 scrub 之后。
  /// 生产路径恒为 null（`main.dart` 从不赋值），不影响上报。
  @visibleForTesting
  static void Function(String event, Map<String, Object>? properties)? debugCaptureSink;

  /// 自定义事件上报。properties 先经 [scrub] 剥离敏感键再上报；
  /// 白名单内事件用**同一份净化后属性**同步分发 AppsFlyer（单通路，无旁路）。
  static Future<void> capture(String event, [Map<String, Object>? properties]) async {
    final clean = properties == null ? null : scrub(properties);
    debugCaptureSink?.call(event, clean);
    if (isAppsFlyerEvent(event)) {
      AppsFlyerClient.instance.logEvent(event, clean);
    }
    try {
      await Posthog().capture(eventName: event, properties: clean);
    } catch (e) {
      debugPrint('[Analytics] capture failed: $e');
    }
  }

  /// 页面浏览事件（PostHog `$screen`）。
  ///
  /// **为什么需要它**（Story 6.1 AC2，PRD 点名的 P0 缺口）：底部 Tab 走
  /// `StatefulShellRoute.goBranch` 切分支，**不 push 根路由** → `PosthogObserver` 收不到
  /// `didPush`，四个 Tab 根页此前一个浏览事件都没有，落地页分流是否生效无从验证。
  /// 这里补的是「Tab 根页」这一层，详情页仍由 observer 自动上报，两者不重复。
  ///
  /// [name] 必须是稳定的受控字面量（Tab 名 / 路由名），**不得**传 UI 文案。
  static Future<void> screen(String name) async {
    debugCaptureSink?.call(r'$screen', {r'$screen_name': name});
    try {
      await Posthog().screen(screenName: name);
    } catch (e) {
      debugPrint('[Analytics] screen failed: $e');
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

  /// 防御性剥离敏感键，返回新 map。三道规则（键归一化后比对，**递归**嵌套 map 与 List）：
  /// PII/健康键丢弃、自由文本键丢弃、超长字符串值丢弃。纯函数，L0 可测。
  ///
  /// 🔴 **List 也要递归**（Story 9.2 补）：行级归因把 `items[]` 这种「map 的数组」
  /// 带进了埋点。此前 List 是整块透传的 —— 只要有人往行里加个收件人名，
  /// 它会绕过全部三道规则直接发出去。NFR-5 不接受「这一层碰巧没人放 PII」。
  static Map<String, Object> scrub(Map<String, Object> props) {
    final out = <String, Object>{};
    props.forEach((k, v) {
      if (_isPiiKey(k) || _isFreeTextKey(k)) return;
      if (v is String && v.length > _maxStringValueLen) return;
      out[k] = _scrubValue(v);
    });
    return out;
  }

  /// 递归净化单个值：map → 逐键过规则；list → 逐元素递归；其余原样。
  static Object _scrubValue(Object v) {
    if (v is Map) {
      final nested = <String, Object>{};
      v.forEach((nk, nv) {
        if (nv != null) nested[nk.toString()] = nv as Object;
      });
      return scrub(nested);
    }
    if (v is List) {
      // 🔴 超长字符串元素同样丢弃 —— 规则在 List 里不该打折。
      return [
        for (final e in v)
          if (e != null && !(e is String && e.length > _maxStringValueLen))
            _scrubValue(e as Object),
      ];
    }
    return v;
  }

  /// 键归一化（小写 + 去非字母数字）后比对黑名单：snake_case/kebab 与 camelCase 同名一并命中。
  /// 🔴 **后缀也要拦**（Story 9.3 全量核对补）：黑名单原先是「归一化后精确相等」，
  /// 于是 `receiver_name` / `receiver_phone` / `address_line` 一个都不命中 ——
  /// 而这三个正是 Epic 2 收货地址引入、NFR-5 点名新增的禁记项。
  /// 精确表继续留着（`ip` / `dob` 这类短词不适合做后缀），后缀表只收长到不会误伤的词。
  /// ⚠️ 代价是 `product_name` 之类也会被丢 —— 那本就是自由文本，看板该用 `product_id`。
  static const Set<String> _piiKeySuffixes = {
    'name', 'phone', 'address', 'email', 'whatsapp',
  };

  static bool _isPiiKey(String key) {
    final k = _normalizeKey(key);
    if (_piiKeys.contains(k)) return true;
    return _piiKeySuffixes.any(k.endsWith);
  }

  static bool _isFreeTextKey(String key) => _freeTextKeys.contains(_normalizeKey(key));

  static String _normalizeKey(String key) =>
      key.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]'), '');
}
