import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/features/ops/data/ops_popup_repository.dart';

/// Story 5.4 · AC4 / NFR5：候选接口「失败即空」，曝光上报吞异常（真 dio + 假适配器，L0）。
void main() {
  test('路径常量', () {
    expect(ApiPaths.opsPopupCandidates, endsWith('/api/v1/ops-popups/candidates'));
    expect(ApiPaths.opsPopupImpressions('abc'), endsWith('/api/v1/ops-popups/abc/impressions'));
  });

  test('{"items": [...]} 按序解析，单条不合法丢弃不影响其他条', () async {
    final adapter = _BodyAdapter('''
      {"items": [
        {"token": "a", "frequency": "ONCE", "imageUrlId": "https://x/a", "buttonTextId": "A",
         "target": {"type": "NONE"}},
        {"token": "bad", "frequency": "HOURLY", "imageUrlId": "https://x/b", "buttonTextId": "B"},
        {"token": "c", "frequency": "DAILY", "imageUrlId": "https://x/c", "buttonTextId": "C"}
      ]}''');
    final repo = OpsPopupRepository(dio: Dio()..httpClientAdapter = adapter);
    final list = await repo.fetchCandidates();
    expect(list.map((e) => e.token), ['a', 'c']);
    expect(adapter.requests.single.method, 'GET');
    expect(adapter.requests.single.path, ApiPaths.opsPopupCandidates);
  });

  test('空 items / 形状不对 / 非 2xx / 抛异常 → 空列表，不抛', () async {
    for (final body in ['{"items": []}', '{}', '[]', 'null', '{"items": "x"}']) {
      final repo = OpsPopupRepository(dio: Dio()..httpClientAdapter = _BodyAdapter(body));
      expect(await repo.fetchCandidates(), isEmpty, reason: body);
    }
    final r500 = OpsPopupRepository(dio: Dio()..httpClientAdapter = _BodyAdapter('{}', status: 500));
    expect(await r500.fetchCandidates(), isEmpty);
    final boom = OpsPopupRepository(dio: Dio()..httpClientAdapter = _BodyAdapter.throwing());
    expect(await boom.fetchCandidates(), isEmpty);
  });

  testWidgets('5 秒超时 → 空列表', (tester) async {
    final repo = OpsPopupRepository(dio: Dio()..httpClientAdapter = _BodyAdapter.hanging());
    List<Object>? result;
    unawaited(repo.fetchCandidates().then((v) => result = v));
    await tester.pump(const Duration(milliseconds: 4999));
    expect(result, isNull);
    await tester.pump(const Duration(milliseconds: 2));
    expect(result, isEmpty);
  });

  test('曝光上报：POST 到 token 路径；失败吞掉', () async {
    final ok = _BodyAdapter('', status: 204);
    await OpsPopupRepository(dio: Dio()..httpClientAdapter = ok).reportImpression('tok1');
    expect(ok.requests.single.method, 'POST');
    expect(ok.requests.single.path, ApiPaths.opsPopupImpressions('tok1'));

    await expectLater(
        OpsPopupRepository(dio: Dio()..httpClientAdapter = _BodyAdapter('{}', status: 404))
            .reportImpression('x'),
        completes);
    await expectLater(
        OpsPopupRepository(dio: Dio()..httpClientAdapter = _BodyAdapter.throwing())
            .reportImpression('x'),
        completes);
  });
}

/// 固定返回一段原始响应体的适配器（照 `test/shop/shop_product_paging_test.dart` 的 `_BodyAdapter`）。
class _BodyAdapter implements HttpClientAdapter {
  _BodyAdapter(this.body, {this.status = 200}) : _mode = 0;
  _BodyAdapter.throwing()
      : body = '',
        status = 0,
        _mode = 1;
  _BodyAdapter.hanging()
      : body = '',
        status = 0,
        _mode = 2;

  final String body;
  final int status;
  final int _mode;
  final List<RequestOptions> requests = [];

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream,
      Future<void>? cancelFuture) async {
    requests.add(options);
    if (_mode == 1) throw StateError('boom');
    if (_mode == 2) return Completer<ResponseBody>().future;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  @override
  void close({bool force = false}) {}
}
