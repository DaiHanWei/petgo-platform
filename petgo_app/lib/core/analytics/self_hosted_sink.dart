import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import '../network/dio_client.dart' show kApiBaseUrl;
import 'anonymous_id.dart';
import 'event_queue_file.dart';

/// 一次上报请求的结果。[retryAfter] 为原始 `Retry-After` 头（秒数或 HTTP 日期）。
class SinkResponse {
  const SinkResponse(this.statusCode, {this.retryAfter});
  final int statusCode;
  final String? retryAfter;
}

/// 传输函数：POST 已序列化的请求体（`{"batch":[...]}`），返回状态码。
/// 网络异常 / 超时直接抛出，由 sink 按「断网」退避。
typedef SinkTransport = Future<SinkResponse> Function(String body);

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
/// - 401 → 保留 + 指数退避（本 story 不带 JWT，正常不会出现；Story 2.2 加 JWT 后在此接刷新）；
/// - 其他 5xx / 非预期状态（如 404）/ 网络异常 / 超时 → 保留，指数退避
///   （5s 起 ×2，封顶 5 分钟，±20% 抖动），成功一次归零。
///
/// **不打扰主流程**：debug 包 `enabled=false`，[enqueue] 直接返回、不写文件、不起定时器；
/// 一切异常内部吞掉，日志只打条数 / 状态码，绝不打事件内容。
class SelfHostedSink with WidgetsBindingObserver {
  SelfHostedSink._({
    required this.enabled,
    SinkTransport? transport,
    DateTime Function()? now,
    double Function()? jitter,
    EventQueueStore? store,
    Future<EventQueueStore> Function()? openStore,
    bool registerLifecycle = true,
  })  : _transport = transport ?? _dioTransport(),
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
    DateTime Function()? now,
    double Function()? jitter,
    EventQueueStore? store,
  }) =>
      SelfHostedSink._(
        enabled: enabled,
        transport: transport,
        now: now,
        jitter: jitter ?? () => 1.0,
        store: store ?? MemoryEventQueueStore(),
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

  static const Duration defaultRetryAfter = Duration(seconds: 60);
  static const Duration backoffBase = Duration(seconds: 5);
  static const Duration backoffMax = Duration(minutes: 5);

  /// 启动前缓冲上限（队列文件还没打开时收到的事件）。
  static const int _maxPending = kEventQueueMaxEvents;

  final bool enabled;
  final SinkTransport _transport;
  final DateTime Function() _now;
  final double Function() _jitter;
  final Future<EventQueueStore> Function() _openStore;
  final bool _registerLifecycle;

  EventQueueStore? _store;
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

  /// `Analytics.init()` 调一次：打开队列文件、裁剪并加载上次未发完的事件、合并启动前缓冲、
  /// 注册生命周期观察者、起 30 秒定时器，然后立即补发一次。失败只降级为内存队列，不抛。
  Future<void> start() async {
    if (!enabled || _store != null || _starting) return;
    _starting = true;
    try {
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
        // Story 2.2 入队时绑定。
        'userId': null,
        'properties': properties ?? const <String, Object>{},
        // Story 2.5 填环境字段。
        'context': const <String, Object>{},
      };
      // 序列化一次：校验能编码 + 量体积；超 32 KB 整批会 413，提前丢掉。
      final bytes = utf8.encode(jsonEncode(envelope)).length;
      if (bytes + _sentAtReserve > maxEventBytes) {
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
      SinkResponse resp;
      try {
        resp = await _transport('{"batch":[${encoded.join(',')}]}');
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
        // 401 / 404 / 其他 5xx / 非预期：不丢，退避。
        // TODO(Story 2.2)：加 JWT 后 401 改为「单飞刷新 token 后重发一次，刷新失败去掉 JWT 匿名重发」，
        // 仍不得整批丢弃、不得快速重试。
        _backoff();
        debugPrint('[Analytics] batch kept: status=$code');
        return;
      }
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

  static double _defaultJitter() => 0.8 + Random().nextDouble() * 0.4;

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
    return (body) async {
      final r = await dio.post<void>(path, data: body);
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
