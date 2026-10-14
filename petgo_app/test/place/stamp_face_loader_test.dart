import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/stamp_face_loader.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

class _Adapter implements HttpClientAdapter {
  _Adapter(this.handler);
  final Future<ResponseBody> Function() handler;
  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<Uint8List>? s, Future<void>? c) => handler();
  @override
  void close({bool force = false}) {}
}

Dio _dio(Future<ResponseBody> Function() h) => Dio()..httpClientAdapter = _Adapter(h);

class _Bundle extends CachingAssetBundle {
  String? asked;
  @override
  Future<ByteData> load(String key) async {
    asked = key;
    return ByteData.sublistView(Uint8List.fromList([1, 2, 3]));
  }
}

void main() {
  test('urlSuccess', () async {
    final d = _dio(() async => ResponseBody.fromBytes([9, 8, 7], 200));
    expect(await loadStampFace(url: 'https://x/y.png', dio: d), Uint8List.fromList([9, 8, 7]));
  });

  test('url404ReturnsNull', () async {
    final d = _dio(() async => ResponseBody.fromBytes([1], 404));
    expect(await loadStampFace(url: 'https://x/y.png', dio: d), isNull);
  });

  test('emptyBytesReturnsNull', () async {
    final d = _dio(() async => ResponseBody.fromBytes([], 200));
    expect(await loadStampFace(url: 'https://x/y.png', dio: d), isNull);
  });

  test('timeoutReturnsNull', () async {
    final d = _dio(() => Completer<ResponseBody>().future);
    expect(
      await loadStampFace(url: 'https://x/y.png', dio: d, timeout: const Duration(milliseconds: 50)),
      isNull,
    );
  });

  test('noUrlUsesDefaultAsset', () async {
    final b = _Bundle();
    final r = await loadStampFace(type: PlaceType.cafe, bundle: b);
    expect(b.asked, 'assets/place_stamp/cafe.png');
    expect(r, Uint8List.fromList([1, 2, 3]));
  });

  test('noUrlNoTypeReturnsNull', () async {
    expect(await loadStampFace(bundle: _Bundle()), isNull);
  });
}
