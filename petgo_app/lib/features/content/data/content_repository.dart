import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/content_type.dart';

/// 内容发布数据层（Story 2.3）。带 `Idempotency-Key` 去重。抽象便于测试注入 fake。
abstract class ContentRepository {
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls,
    required String idempotencyKey,
  });
}

class DioContentRepository implements ContentRepository {
  DioContentRepository(this.dio);

  final Dio dio;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    required String idempotencyKey,
  }) async {
    final data = <String, dynamic>{'type': type.wire};
    if (petId != null) data['petId'] = petId;
    if (text != null && text.isNotEmpty) data['text'] = text;
    if (imageUrls.isNotEmpty) data['imageUrls'] = imageUrls;

    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.contentPosts,
      data: data,
      options: Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
    );
    return resp.data!['id'] as int;
  }
}

final Provider<ContentRepository> contentRepositoryProvider =
    Provider<ContentRepository>((ref) => DioContentRepository(ref.read(dioProvider)));
