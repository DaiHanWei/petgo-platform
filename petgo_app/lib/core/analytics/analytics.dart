import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:posthog_flutter/posthog_flutter.dart';

import 'appsflyer_client.dart';
import 'button_ids.dart';
import 'event_names.dart';
import 'lifecycle_events.dart';
import 'meta_client.dart';
import 'self_hosted_sink.dart';
import 'tiktok_client.dart';

/// 前端行为分析门面：事件底层已换成自建上报（[SelfHostedSink] → `POST /api/v1/analytics/batch`，
/// analytics-pipeline Story 2.1）；identify / reset 已改走自建身份绑定（Story 2.2）。
/// PostHog 的 init / screen 残留由 Story 2.6 统一清理。
///
/// 设计约束（CLAUDE.md 护栏）：
/// - Project Token / Host 走 dart-define 注入，带生产默认值，对齐 `dio_client.dart`。
/// - **绝不**上报 PII（email/昵称/姓名/电话）或健康数据。
/// - 用户标识一律用 [distinctIdFor] 的哈希，**不上报明文数字 id**（原 PostHog 专项豁免
///   `internal_user_id` 自建库不沿用，Story 2.2 AC2；埋点库内部 `user_id` 由后端从 JWT / 关联表得出）。
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
  ///
  /// 自建 sink 启动（打开队列文件、加载上次未发完的事件、注册生命周期观察者、起定时器）与
  /// PostHog setup **互相独立**：任何一个失败都不影响另一个。sink 在队列加载完成前收到的事件
  /// 先放内存缓冲，加载完成后合并写盘，不丢。
  ///
  /// 生命周期事件（Story 2.3）在 sink 启动之后开始（[LifecycleEvents.start]），不等 PostHog。
  static Future<void> init() async {
    await Future.wait([
      _startSelfHostedSink().then((_) => _startLifecycleEvents()),
      _setupPosthog(),
    ]);
  }

  static Future<void> _startLifecycleEvents() async {
    try {
      await LifecycleEvents.start();
    } catch (e) {
      debugPrint('[Analytics] lifecycle start failed: ${e.runtimeType}');
    }
  }

  static Future<void> _startSelfHostedSink() async {
    try {
      await SelfHostedSink.instance.start();
    } catch (e) {
      debugPrint('[Analytics] sink start failed: ${e.runtimeType}');
    }
  }

  static Future<void> _setupPosthog() async {
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

  /// 测试注入点：非 null 时 identify / reset / capture 用它代替 [SelfHostedSink.instance]
  /// （debug 构建下生产单例 `enabled=false`，测不到信封）。生产路径恒为 null。
  @visibleForTesting
  static SelfHostedSink? debugSinkOverride;

  static SelfHostedSink get _sink => debugSinkOverride ?? SelfHostedSink.instance;

  /// 登录成功后关联用户（analytics-pipeline Story 2.2，FR-2 / A4 / A7）。
  ///
  /// ① sink 设当前用户标识 `distinctIdFor(userId)` —— 之后入队的事件信封 `userId` 都带它；
  /// ② 上报 `user_identified`，属性只放 `role`（USER / VET，非标识；留存看板据此剔除兽医号）。
  ///    **不带** `internal_user_id`、不带 `distinct_id` —— 用户标识已在信封 `userId` 上，
  ///    后端凭「信封 `anonymousId` + `userId` == token 用户」建关联行；
  /// ③ 立即 flush（不 await）：让 `user_identified` 随**本账号的 JWT** 尽快送达。若拖到下一个
  ///    账号登录后才发，`userId` 与 token 用户不符，后端不建关联（`LinkTableIdentityResolver`）。
  ///
  /// 同一哈希值同时设为 AppsFlyer CUID（跨端归因 P0）/ TikTok / Meta user id。
  static Future<void> identifyUser(int userId, {String? role}) async {
    final key = distinctIdFor(userId);
    AppsFlyerClient.instance.setUserId(key);
    unawaited(TikTokClient.instance.setUserId(key));
    unawaited(MetaClient.instance.setUserId(key));
    try {
      final sink = _sink;
      sink.setUserKey(key);
      await capture(AnalyticsEvents.userIdentified, {'role': ?role});
      unawaited(sink.flush());
    } catch (e) {
      debugPrint('[Analytics] identify failed: ${e.runtimeType}');
    }
  }

  /// 登出 / 续期失败 / 换号前 → 解除关联，回到匿名。AppsFlyer CUID / TikTok / Meta 关联同步清空
  /// （防换账号串数据）。
  ///
  /// Story 2.2：① 先触发一次 flush（不 await、失败不阻塞）—— 让上一账号还在队列里的事件趁旧
  /// 身份尽量先发出（换号前先发，AC3）；② 再清 sink 的用户标识。**不重置匿名 id**（A3 / FR-5），
  /// 登录前后靠同一匿名 id 连成同一个人。已入队事件的 `userId` 不受影响（入队即绑定）。
  static Future<void> reset() async {
    AppsFlyerClient.instance.clearUserId();
    unawaited(TikTokClient.instance.clearUserId());
    unawaited(MetaClient.instance.clearUserId());
    try {
      final sink = _sink;
      unawaited(sink.flush());
      sink.setUserKey(null);
    } catch (e) {
      debugPrint('[Analytics] reset failed: ${e.runtimeType}');
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

  /// 同步回传 Meta / TikTok 的注册完成事件（投放渠道看注册转化率用）。
  static const String adRegistrationEvent = 'af_complete_registration';

  /// 同步回传 Meta / TikTok 的付款成功事件（投放渠道看付费率 / ROAS 用）。只能经 [capturePurchase] 发。
  static const String adPurchaseEvent = 'af_purchase';

  /// 事件是否分发 AppsFlyer（纯函数，L0 可测）。
  static bool isAppsFlyerEvent(String event) => appsflyerEvents.contains(event);

  /// 测试观察点（Story 6.1）：非 null 时，每条 [capture] / [screen] 都同步回调一次
  /// **净化后**的属性（含金额键 —— 即自建上报与 AppsFlyer 两路实收的形态；FR-1：数据进自有库，
  /// 金额允许上报）。埋点断言必须看到端上真正发出的形态，所以钩子挂在 scrub 之后。
  /// 生产路径恒为 null（`main.dart` 从不赋值），不影响上报。
  @visibleForTesting
  static void Function(String event, Map<String, Object>? properties)? debugCaptureSink;

  /// 自定义事件上报。properties 先经 [scrub] 剥离敏感键，再原样（含金额键）进自建上报队列；
  /// 白名单内事件用**同一份净化后属性**同步分发 AppsFlyer（单通路，无旁路）。
  static Future<void> capture(String event, [Map<String, Object>? properties]) async {
    final clean = properties == null ? null : scrub(properties);
    debugCaptureSink?.call(event, clean);
    if (isAppsFlyerEvent(event)) {
      AppsFlyerClient.instance.logEvent(event, clean);
    }
    // Meta / TikTok 自归因：目前只回传「注册完成」一个转化（2026-10-09，看渠道注册转化率 +
    // 投放可按注册优化）。复用 AppsFlyer 的同名事件，单一触发点（只有 isNewUser 才会报）。
    if (event == adRegistrationEvent) {
      final method = clean?['af_registration_method'];
      unawaited(MetaClient.instance.logRegistration(method is String ? method : null));
      unawaited(TikTokClient.instance.logRegistration());
    }
    // 现金付款成功（所有 QRIS 入口统一走 [capturePurchase]）→ 两家的标准购买事件，看付费率 / ROAS。
    if (event == adPurchaseEvent) {
      final revenue = clean?['af_revenue'];
      final purpose = clean?['af_content_id'];
      final orderId = clean?['af_order_id'];
      final amount = revenue is num ? revenue : null;
      unawaited(MetaClient.instance.logPurchase(
        amountIdr: amount,
        purpose: purpose is String ? purpose : null,
        orderId: orderId is String ? orderId : null,
      ));
      unawaited(TikTokClient.instance.logPurchase(
        amountIdr: amount,
        purpose: purpose is String ? purpose : null,
        orderId: orderId is String ? orderId : null,
      ));
    }
    // 自建上报（Story 2.1）：同步入本地队列，发送由 sink 攒批异步完成；enqueue 内部吞全部异常。
    _sink.enqueue(event, clean);
  }

  /// **现金付款成功**的唯一上报入口（2026-10-09 起覆盖全部 QRIS 入口：PawCoin 充值 / 兽医问诊 /
  /// AI 解锁 / KTP 高清图 / 一次性解锁 / 商城订单）。AppsFlyer `af_purchase` 与 Meta / TikTok
  /// 购买事件都由它派生，三家同一口径。
  ///
  /// 🔴 **口径 = 用户真实付出的现金（IDR）**：
  /// - 只在 QRIS 到账时调。**PawCoin 消费一律不调**——钱在充值那一刻已经计过，再计就是双倍收入。
  /// - 混合支付（PawCoin + 现金）只计现金那一段。
  /// - [amountIdr] 未知（价格没取到）时传 null：照样计一次付款（付费率要的是次数），但不带金额，
  ///   不能拿 0 冒充（会把 ROAS 拉低）。
  /// - [purpose] 用支付用途代号（与后端 `PaymentPurpose` 同名，如 `VET_CONSULT`），不带任何用户内容。
  /// - [orderRef] 用不可枚举的支付号 / 订单号，供两家按订单去重（不传自增 id）。
  ///
  /// ⚠️ 已知盲区：用户关掉付款面板后才付款，App 侦测不到到账 —— 客户端口径会漏这类单。
  /// 要全量只能改由后端到账时走 Meta Conversions API / TikTok Events API。
  static Future<void> capturePurchase({
    required int? amountIdr,
    required String purpose,
    String? orderRef,
  }) =>
      Analytics.capture('af_purchase', {
        if (amountIdr != null && amountIdr > 0) 'af_revenue': amountIdr,
        'af_currency': 'IDR',
        'af_content_id': purpose,
        'af_quantity': 1,
        if (orderRef != null && orderRef.isNotEmpty) 'af_order_id': orderRef,
      });

  /// T-7 `signup_succeeded` 的**唯一**上报入口（登录页与引导浮层两条注册路径共用）。
  ///
  /// 除事件本身，顺带以 `$set_once` 固化两个 person property：`signup_date` 与
  /// `first_entry_source`。它们是留存看板「按注册月分群」「注册来源 × 留存」的唯一来源 ——
  /// 在此之前 person property 只有 `internal_user_id`，这两个切法根本做不了。
  ///
  /// **为什么挂在事件上、而不是单独调 `setPersonProperties`：**
  /// ① 与事件原子同发，两者不会漂开；
  /// ② 不依赖 identify 与本次上报的先后 —— 事件落到哪个 person，属性就跟到哪个；
  /// ③ 走 [capture] 一条通路，因此既过 [scrub]，也能被 `debugCaptureSink` 在 L0 断言到
  ///    （`setPersonProperties` 绕开 sink，只能靠 platform channel mock 才测得动）。
  ///
  /// ⚠️ **配看板前必读的三条口径：**
  /// - **是 `$set_once` 不是 `$set`**：identify 每次登录都调，若用 `$set`，老用户的
  ///   `signup_date` 会被刷成「最近一次登录日」，整列失去意义。
  /// - **存量用户没有这两个属性**：后端 `UserProfileResponse` 不下发 `createdAt`，
  ///   补不了历史。按 `signup_date` 分群时**分母只含本次上线之后注册的人**，不是全体用户。
  /// - **`signup_date` 取设备本地时钟的 UTC 日期**，改系统时间即可伪造。分群够用，
  ///   **不可作对账依据**。
  static Future<void> captureSignupSucceeded(
    String entrySource, {
    @visibleForTesting DateTime? now,
  }) =>
      // 🔴 **必须写成 `Analytics.capture(` 这个自限定形式，不能简化成裸 `capture(`**：
      // 埋点守卫（`test/analytics/*_events_test.dart`）是从 `lib/` 里正则提取
      // `Analytics\.capture\(\s*'([A-Za-z0-9_]+)'` 的字面量来对账的。写成裸调用，
      // `signup_succeeded` 会从提取集里整个消失 —— T-7 的断言当场变红，
      // 而真正危险的是反过来：哪天守卫放宽了，事件就此悄悄脱离对账，看板恒空。
      Analytics.capture('signup_succeeded', {
        'entry_source': entrySource,
        r'$set_once': {
          'signup_date': (now ?? DateTime.now()).toUtc().toIso8601String().substring(0, 10),
          'first_entry_source': entrySource,
        },
      });

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
    // V1.3.0 Story 3-3：客服 WhatsApp 深链入口。
    ButtonId.supportWhatsapp,
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
