import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import '../network/dio_client.dart' show kApiBaseUrl;
import '../storage/secure_storage.dart';
import 'analytics.dart' show Analytics;
import 'anonymous_id.dart';
import 'event_queue_file.dart';
import 'production_flags.dart';

/// 一次上报请求的结果。[retryAfter] 为原始 `Retry-After` 头（秒数或 HTTP 日期）。
class SinkResponse {
  const SinkResponse(this.statusCode, {this.retryAfter});
  final int statusCode;
  final String? retryAfter;
}

/// 传输函数：POST 已序列化的请求体（`{"batch":[...]}`），返回状态码。
/// [bearer] 非 null 时带 `Authorization: Bearer <bearer>`（Story 2.2）；token 只进请求头。
/// 网络异常 / 超时直接抛出，由 sink 按「断网」退避。
typedef SinkTransport = Future<SinkResponse> Function(String body, String? bearer);

/// 读当前 access token（Story 2.2）。返回 null 或抛异常都按「无 token」发送。
typedef SinkTokenReader = Future<String?> Function();

/// 读环境上下文（Story 2.5）。生产为 [AnalyticsEnvironment.load]（只采集一次并缓存）。
typedef SinkEnvironmentLoader = Future<Map<String, Object>> Function();

/// 自建埋点上报器（analytics-pipeline Story 2.1，FR-1–3 / A2）。
///
/// `Analytics.capture` 经白名单 + `scrub()` 后调 [enqueue]：同步组装信封 → 本地队列文件
/// → 攒批 `POST /api/v1/analytics/batch`。
///
/// **触发**：累计 ≥20 条；30 秒周期；进后台（`paused` / `hidden`）；回前台（`resumed`，
/// 兼作网络恢复后补发，清零退避）；启动加载旧队列后一次。同一时刻只有一个发送在途，
/// 在途期间的触发合并为结束后再查一次。
///
/// **返回码**（与架构「接口格式」及后端 `AnalyticsBatchController` 一致）：
/// - 2xx（202）→ 删除本批；
/// - 429 → 保留，按 `Retry-After` 暂停（缺失 / 解析失败默认 60 秒），暂停期内所有触发不发；
/// - 503 → 保留；带 `Retry-After`（管道停用时后端会带）时同 429 遵守，并取与退避两者较长者；
///   不带则指数退避；
/// - 400 / 413 → 整批丢弃并计数（防止坏批次永远卡在队头）；
/// - 401 → 带了 JWT 的批次立刻去掉 `Authorization` 原样重发一次（Story 2.2：access token
///   15 分钟过期，过期 token 会被安全链直接 401），重发结果再按本表处理；不刷新 token、不改登录态。
///   未带 JWT 仍 401 → 保留 + 指数退避；
/// - 其他 5xx / 非预期状态（如 404）/ 网络异常 / 超时 → 保留，指数退避
///   （5s 起 ×2，封顶 5 分钟，±20% 抖动），成功一次归零。
///
/// **身份（Story 2.2，FR-2）**：[setUserKey] 设当前用户标识（`Analytics.distinctIdFor` 的哈希，
/// 绝不放明文数字 id），[enqueue] 组装信封时取**那一刻**的值写进 `userId`；信封随即序列化进队列，
/// 之后登出 / 换号不改已入队事件的身份。该值只在内存，冷启动由 `app.dart` 的 auth 监听重新 identify。
/// 发送时读 access token 加 `Authorization`；用独立 Dio，**不挂 `AuthInterceptor`**，埋点请求绝不
/// 触发续期 / 转游客 / 登录弹框。token 不进队列文件、日志、事件属性。
///
/// **环境上下文（Story 2.5）**：[start] 先 await 环境上下文（[AnalyticsEnvironment]，已缓存），
/// 再打开队列。信封 `context` = 环境上下文 + 入队那一刻的 `screen`（`Analytics.currentScreen`：
/// **最近一次 `Analytics.screen()` 传入的页面名**，不保证是当前可见页）。队列打开前入队的事件先放
/// 内存缓冲，`screen` 入队时已取好，环境字段在合并写盘前补上 —— `enqueue` 从不等环境信息。
///
/// **不打扰主流程**：debug 包 `enabled=false`，[enqueue] 直接返回、不写文件、不起定时器；
/// 一切异常内部吞掉，日志只打条数 / 状态码，绝不打事件内容。
class SelfHostedSink with WidgetsBindingObserver {
  SelfHostedSink._({
    required this.enabled,
    SinkTransport? transport,
    SinkTokenReader? readToken,
    DateTime Function()? now,
    double Function()? jitter,
    EventQueueStore? store,
    Future<EventQueueStore> Function()? openStore,
    SinkEnvironmentLoader? loadEnvironment,
    String? Function()? currentScreen,
    bool registerLifecycle = true,
  })  : _transport = transport ?? _dioTransport(),
        _loadEnvironment = loadEnvironment ?? AnalyticsEnvironment.load,
        _currentScreen = currentScreen ?? _analyticsScreen,
        _readToken = readToken ?? _readAccessToken,
        _now = now ?? DateTime.now,
        _jitter = jitter ?? _defaultJitter,
        _openStore = openStore ?? (store != null ? (() async => store) : _openFileStore),
        _registerLifecycle = registerLifecycle && enabled;

  /// 测试构造：注入开关、假传输、假时钟、内存队列；不注册生命周期观察者（测试直接调
  /// [didChangeAppLifecycleState]）。
  @visibleForTesting
  factory SelfHostedSink.test({
    bool enabled = true,
    required SinkTransport transport,
    SinkTokenReader? readToken,
    DateTime Function()? now,
    double Function()? jitter,
    EventQueueStore? store,
    SinkEnvironmentLoader? loadEnvironment,
    String? Function()? currentScreen,
  }) =>
      SelfHostedSink._(
        enabled: enabled,
        transport: transport,
        readToken: readToken ?? () async => null,
        now: now,
        jitter: jitter ?? () => 1.0,
        store: store ?? MemoryEventQueueStore(),
        loadEnvironment: loadEnvironment ?? () async => const <String, Object>{},
        currentScreen: currentScreen ?? () => null,
        registerLifecycle: false,
      );

  /// 生产单例：profile / release 发送，debug 不发。
  static final SelfHostedSink instance = SelfHostedSink._(enabled: !kDebugMode);

  static const String path = '/api/v1/analytics/batch';
  static const int flushAtCount = 20;
  static const Duration flushInterval = Duration(seconds: 30);

  /// 单条序列化上限（A6 / 后端 `maxEventBytes`）。
  static const int maxEventBytes = 32 * 1024;

  /// 单请求最多条数、体积（后端上限 500 KB，留余量）。
  static const int maxBatchEvents = 100;
  static const int maxBatchBytes = 450 * 1024;

  /// `sentAt` 发送时才填，单条体积检查时预留它的字节数。
  static const int _sentAtReserve = 48;

  /// 队列打开前入队的事件，环境字段稍后才补，体积检查时预留（实际不到 400 字节）。
  static const int _contextReserve = 1024;

  static const Duration defaultRetryAfter = Duration(seconds: 60);
  static const Duration backoffBase = Duration(seconds: 5);
  static const Duration backoffMax = Duration(minutes: 5);

  /// 启动前缓冲上限（队列文件还没打开时收到的事件）。
  static const int _maxPending = kEventQueueMaxEvents;

  final bool enabled;
  final SinkTransport _transport;
  final SinkTokenReader _readToken;
  final DateTime Function() _now;
  final double Function() _jitter;
  final Future<EventQueueStore> Function() _openStore;
  final SinkEnvironmentLoader _loadEnvironment;
  final String? Function() _currentScreen;
  final bool _registerLifecycle;

  EventQueueStore? _store;

  /// 环境上下文；与 [_store] 同时赋值（不变式：`_store != null` ⇔ `_env != null`）。
  Map<String, Object>? _env;

  /// 当前用户标识（`distinctIdFor` 哈希）；null = 未登录 / 兽医 / 冷启动恢复前。只在内存。
  String? _currentUserKey;
  final List<Map<String, Object?>> _pending = [];
  bool _starting = false;
  Timer? _timer;

  bool _sending = false;
  bool _again = false;
  int _sinceFlush = 0;

  /// 429（或带 Retry-After 的 503）暂停到此刻；回前台不清。
  DateTime? _pauseUntil;

  /// 指数退避到此刻；回前台清零。
  DateTime? _backoffUntil;
  int _backoffAttempt = 0;

  /// 丢弃计数（超大单条 / 400 / 413 / 序列化失败），只用于日志与测试。
  int _dropped = 0;

  @visibleForTesting
  int get droppedCount => _dropped;

  @visibleForTesting
  DateTime? get pauseUntil => _pauseUntil;

  @visibleForTesting
  DateTime? get backoffUntil => _backoffUntil;

  @visibleForTesting
  bool get isStarted => _store != null;

  @visibleForTesting
  String? get currentUserKey => _currentUserKey;

  /// 设 / 清当前用户标识（Story 2.2）。只影响之后入队的事件。
  void setUserKey(String? userKey) => _currentUserKey = userKey;

  /// `Analytics.init()` 调一次：打开队列文件、裁剪并加载上次未发完的事件、合并启动前缓冲、
  /// 注册生命周期观察者、起 30 秒定时器，然后立即补发一次。失败只降级为内存队列，不抛。
  Future<void> start() async {
    if (!enabled || _store != null || _starting) return;
    _starting = true;
    try {
      Map<String, Object> env;
      try {
        env = await _loadEnvironment();
      } catch (e) {
        debugPrint('[Analytics] environment load failed: ${e.runtimeType}');
        env = const {'isProduction': false};
      }
      EventQueueStore s;
      try {
        s = await _openStore();
        await s.trim(_now());
      } catch (e) {
        debugPrint('[Analytics] queue open failed, using memory: ${e.runtimeType}');
        s = MemoryEventQueueStore();
      }
      // 从这里到 `_store = s` 没有 await：期间不会有新事件漏进缓冲。
      final pending = List.of(_pending);
      _pending.clear();
      // 缓冲期入队的事件：补环境字段（保留入队时取的 screen）后再写盘。
      for (final e in pending) {
        e['context'] = _withScreen(env, (e['context'] as Map?)?['screen'] as String?);
      }
      _env = env;
      _store = s;
      if (pending.isNotEmpty) unawaited(s.append(pending));
      _sinceFlush = pending.length;
      if (_registerLifecycle) {
        try {
          WidgetsBinding.instance.addObserver(this);
        } catch (_) {}
      }
      _timer ??= Timer.periodic(flushInterval, (_) => unawaited(flush()));
      unawaited(flush());
    } catch (e) {
      debugPrint('[Analytics] sink start failed: ${e.runtimeType}');
    } finally {
      _starting = false;
    }
  }

  /// 同步组装信封并入队。门面不 await；任何异常吞掉。
  void enqueue(String event, Map<String, Object>? properties) {
    if (!enabled) return;
    try {
      final envelope = <String, Object?>{
        'messageId': uuidV4(),
        'event': event,
        'timestamp': isoUtcMillis(_now()),
        'anonymousId': AnonymousId.value,
        // Story 2.2：入队那一刻绑定，之后改 _currentUserKey 不影响已入队事件。
        'userId': _currentUserKey,
        'properties': properties ?? const <String, Object>{},
        // Story 2.5：环境字段（队列打开前为空，写盘前补）+ 入队那一刻的 screen，按发生时刻冻结。
        'context': _withScreen(_env ?? const {}, _screenNow()),
      };
      // 序列化一次：校验能编码 + 量体积；超 32 KB 整批会 413，提前丢掉。
      final bytes = utf8.encode(jsonEncode(envelope)).length;
      final reserve = _sentAtReserve + (_env == null ? _contextReserve : 0);
      if (bytes + reserve > maxEventBytes) {
        _drop(1, 'oversize');
        return;
      }
      final s = _store;
      if (s == null) {
        if (_pending.length >= _maxPending) _pending.removeAt(0);
        _pending.add(envelope);
        return;
      }
      unawaited(s.append([envelope]));
      _sinceFlush++;
      if (_sinceFlush >= flushAtCount) unawaited(flush());
    } catch (e) {
      _drop(1, 'encode ${e.runtimeType}');
    }
  }

  String? _screenNow() {
    try {
      return _currentScreen();
    } catch (_) {
      return null;
    }
  }

  static Map<String, Object> _withScreen(Map<String, Object> env, String? screen) =>
      <String, Object>{...env, 'screen': ?screen};

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!enabled) return;
    switch (state) {
      case AppLifecycleState.paused:
      case AppLifecycleState.hidden:
        unawaited(flush());
      case AppLifecycleState.resumed:
        // 回前台兼作「网络恢复后补发」：清退避立即试一次（429 暂停期除外）。
        _backoffUntil = null;
        unawaited(flush());
      case AppLifecycleState.inactive:
      case AppLifecycleState.detached:
        break;
    }
  }

  /// 发送队列里的事件，直到发完、被暂停 / 退避，或出错。永不抛。
  Future<void> flush() async {
    if (!enabled || _store == null) return;
    if (_sending) {
      _again = true;
      return;
    }
    _sending = true;
    try {
      do {
        _again = false;
        if (_blocked()) break;
        await _sendAll(_store!);
      } while (_again);
    } catch (e) {
      debugPrint('[Analytics] flush failed: ${e.runtimeType}');
    } finally {
      _sending = false;
    }
  }

  bool _blocked() {
    final now = _now();
    final p = _pauseUntil;
    if (p != null && now.isBefore(p)) return true;
    final b = _backoffUntil;
    if (b != null && now.isBefore(b)) return true;
    return false;
  }

  Future<void> _sendAll(EventQueueStore store) async {
    await store.trim(_now());
    final all = await store.readAll();
    _sinceFlush = 0;
    var i = 0;
    while (i < all.length) {
      if (_blocked()) return;
      final sentAt = isoUtcMillis(_now());
      final chunk = <Map<String, Object?>>[];
      final encoded = <String>[];
      var size = 12; // {"batch":[]}
      while (i < all.length && chunk.length < maxBatchEvents) {
        final e = Map<String, Object?>.of(all[i])..['sentAt'] = sentAt;
        final s = jsonEncode(e);
        final n = utf8.encode(s).length + 1;
        if (chunk.isNotEmpty && size + n > maxBatchBytes) break;
        chunk.add(e);
        encoded.add(s);
        size += n;
        i++;
      }
      final ids = {for (final e in chunk) e['messageId'] as String};
      final body = '{"batch":[${encoded.join(',')}]}';
      SinkResponse resp;
      try {
        final bearer = await _bearer();
        resp = await _transport(body, bearer);
        if (resp.statusCode == 401 && bearer != null) {
          // 过期 / 失效 JWT：同一批立刻去头重发一次，按匿名请求处理（身份由关联表归属）。
          debugPrint('[Analytics] batch unauthorized, resending without token');
          resp = await _transport(body, null);
        }
      } catch (e) {
        debugPrint('[Analytics] batch send failed: ${e.runtimeType}');
        _backoff();
        return;
      }
      final code = resp.statusCode;
      if (code >= 200 && code < 300) {
        await store.removeByMessageIds(ids);
        _backoffAttempt = 0;
        _backoffUntil = null;
        debugPrint('[Analytics] batch sent: status=$code count=${ids.length}');
      } else if (code == 400 || code == 413) {
        await store.removeByMessageIds(ids);
        _drop(ids.length, 'status=$code');
      } else if (code == 429) {
        _pause(parseRetryAfter(resp.retryAfter, _now()) ?? defaultRetryAfter);
        debugPrint('[Analytics] batch throttled: status=429');
        return;
      } else if (code == 503) {
        final ra = parseRetryAfter(resp.retryAfter, _now());
        final b = _backoff();
        if (ra != null) _pause(ra > b ? ra : b);
        debugPrint('[Analytics] batch unavailable: status=503');
        return;
      } else {
        // 401（已去头重发过或本就没带 token）/ 404 / 其他 5xx / 非预期：不丢，退避。
        _backoff();
        debugPrint('[Analytics] batch kept: status=$code');
        return;
      }
    }
  }

  /// 读 access token；空串 / 异常一律按无 token。
  Future<String?> _bearer() async {
    try {
      final t = await _readToken();
      return (t == null || t.isEmpty) ? null : t;
    } catch (e) {
      debugPrint('[Analytics] token read failed: ${e.runtimeType}');
      return null;
    }
  }

  void _pause(Duration d) => _pauseUntil = _now().add(d);

  /// 进入下一档退避，返回本次退避时长。
  Duration _backoff() {
    final base = backoffBase.inMilliseconds * pow(2, min(_backoffAttempt, 10));
    final capped = min(base, backoffMax.inMilliseconds.toDouble());
    final d = Duration(milliseconds: (capped * _jitter()).round());
    _backoffAttempt++;
    _backoffUntil = _now().add(d);
    return d;
  }

  void _drop(int n, String why) {
    _dropped += n;
    debugPrint('[Analytics] events dropped: count=$n reason=$why');
  }

  @visibleForTesting
  void dispose() {
    _timer?.cancel();
    _timer = null;
    if (_registerLifecycle) {
      try {
        WidgetsBinding.instance.removeObserver(this);
      } catch (_) {}
    }
  }

  static String? _analyticsScreen() => Analytics.currentScreen;

  static double _defaultJitter() => 0.8 + Random().nextDouble() * 0.4;

  static Future<String?> _readAccessToken() => SecureTokenStore().readAccess();

  static Future<EventQueueStore> _openFileStore() async =>
      EventQueueFile(await analyticsDirectory());

  /// 独立 `Dio`：**不用 `dioProvider`**（那上面的 `AuthInterceptor` 一个 401 就会把用户
  /// 踢成游客并弹登录框，另有日志 / 增长拦截器）。baseUrl 唯一来源 `kApiBaseUrl`。
  static SinkTransport _dioTransport() {
    final dio = Dio(BaseOptions(
      baseUrl: kApiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 15),
      contentType: Headers.jsonContentType,
      validateStatus: (_) => true,
    ));
    return (body, bearer) async {
      final r = await dio.post<void>(
        path,
        data: body,
        options: bearer == null ? null : Options(headers: {'Authorization': 'Bearer $bearer'}),
      );
      return SinkResponse(r.statusCode ?? 0, retryAfter: r.headers.value('retry-after'));
    };
  }
}

/// UTC ISO-8601，毫秒精度，带 `Z`（如 `2026-11-12T03:04:05.678Z`）。
String isoUtcMillis(DateTime t) {
  final u = t.toUtc();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${u.year.toString().padLeft(4, '0')}-${two(u.month)}-${two(u.day)}'
      'T${two(u.hour)}:${two(u.minute)}:${two(u.second)}'
      '.${u.millisecond.toString().padLeft(3, '0')}Z';
}

/// 解析 `Retry-After`：非负整数秒，或 HTTP 日期。无法解析返回 null。
Duration? parseRetryAfter(String? header, DateTime now) {
  if (header == null) return null;
  final v = header.trim();
  if (v.isEmpty) return null;
  final secs = int.tryParse(v);
  if (secs != null) return secs < 0 ? null : Duration(seconds: secs);
  try {
    final d = HttpDate.parse(v).difference(now.toUtc());
    return d.isNegative ? Duration.zero : d;
  } catch (_) {
    return null;
  }
}
