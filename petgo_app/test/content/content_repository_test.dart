import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';

/// V1.3.4 Story 2.4 · AC7.2：发布请求体里的 `imageCropOffsets` 序列化口径。
///
/// - 至少一个非 null 才写键；元素 null 原样，非 null 先夹到 [0,1] 再保留 3 位小数；
/// - 全为 null（无超界图）→ **整键不出现**，与 V1.3.3 请求体逐字节一致（后端列为 NULL）。
void main() {
  Future<Map<String, dynamic>> send(List<double?> offsets, {int images = 0}) async {
    late RequestOptions sent;
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
        sent = o;
        h.resolve(Response(requestOptions: o, statusCode: 200, data: {'id': 1}));
      }));
    await DioContentRepository(dio).publish(
      type: ContentType.daily,
      imageUrls: [for (var i = 0; i < (images == 0 ? offsets.length : images); i++) 'https://cdn/$i.jpg'],
      imageCropOffsets: offsets,
      idempotencyKey: 'k',
    );
    return sent.data as Map<String, dynamic>;
  }

  test('非 null 保留 3 位小数，null 原样占位', () async {
    final body = await send([0.12345, null]);
    expect(body['imageCropOffsets'], [0.123, null]);
  });

  test('全为 null → 请求体不出现该键', () async {
    final body = await send([null, null]);
    expect(body.containsKey('imageCropOffsets'), isFalse);
  });

  test('不传（默认空）→ 请求体不出现该键', () async {
    final body = await send(const [], images: 2);
    expect(body.containsKey('imageCropOffsets'), isFalse);
  });

  test('越界值先夹到 [0,1]', () async {
    final body = await send([1.2]);
    expect(body['imageCropOffsets'], [1.0]);
    final low = await send([-0.4, 0.5]);
    expect(low['imageCropOffsets'], [0.0, 0.5]);
  });

  group('V1.4.0 multi-pet Story 3.3 · AC3.1：petIds', () {
    Future<Map<String, dynamic>> body({required ContentType type, List<int> petIds = const [], int? petId}) async {
      late RequestOptions sent;
      final dio = Dio()
        ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
          sent = o;
          h.resolve(Response(requestOptions: o, statusCode: 200, data: {'id': 1}));
        }));
      await DioContentRepository(dio)
          .publish(type: type, text: 'hi', petIds: petIds, petId: petId, idempotencyKey: 'k');
      return sent.data as Map<String, dynamic>;
    }

    test('Diary：请求体同时有 petIds（原序）与 petId = 第一只', () async {
      final b = await body(type: ContentType.growthMoment, petIds: [3, 8]);
      expect(b['petIds'], [3, 8]);
      expect(b['petId'], 3);
    });

    test('Moment：两者都不写（老路径逐字节不变）', () async {
      final b = await body(type: ContentType.daily);
      expect(b.containsKey('petIds'), isFalse);
      expect(b.containsKey('petId'), isFalse);
    });

    test('只给 petId（老调用方）→ 只写 petId', () async {
      final b = await body(type: ContentType.growthMoment, petId: 5);
      expect(b['petId'], 5);
      expect(b.containsKey('petIds'), isFalse);
    });
  });
}
