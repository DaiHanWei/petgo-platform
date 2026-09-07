import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/content_type.dart';
import '../domain/feed_image_layout.dart';
import '../domain/feed_item.dart' show kVisibilityPrivate, kVisibilityPublic;

/// 内容发布数据层（Story 2.3）。带 `Idempotency-Key` 去重。抽象便于测试注入 fake。
abstract class ContentRepository {
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls,
    /// 与 [imageUrls] **同序等长**的原始宽高（V1.1.6 Story 3.5）。
    /// 量不出来的位置留 null；🛡 长度对不上后端会**整组作废**，不做部分采信。
    List<ImageSize?> imageSizes,
    DateTime? eventDate,
    required String idempotencyKey,
    /// Story 4.2 同步开关：`false` = 只保存在 Diary（`visibility=PRIVATE`）。
    bool syncToMoment,
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
    List<ImageSize?> imageSizes = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
  }) async {
    final data = <String, dynamic>{'type': type.wire};
    // Story 4.2 同步开关 → Story 4.1 的 visibility 字段：关同步 = 仅自己可见。
    // 只在创建时可传；发布后不可更改（FR-83：唯一途径是删除该条内容）。
    data['visibility'] = syncToMoment ? kVisibilityPublic : kVisibilityPrivate;
    if (petId != null) data['petId'] = petId;
    if (text != null && text.isNotEmpty) data['text'] = text;
    if (imageUrls.isNotEmpty) data['imageUrls'] = imageUrls;
    // 一个都测不出来时干脆不传，让服务端整组兜底（传一串全 null 没有意义）。
    if (imageSizes.isNotEmpty && imageSizes.any((e) => e != null)) {
      data['imageSizes'] =
          imageSizes.map((e) => e == null ? null : {'w': e.w, 'h': e.h}).toList();
    }
    // 事件日期 yyyy-MM-dd（仅成长日历传；后端对日常/科普强制忽略，F9）。
    if (eventDate != null) {
      final d = eventDate;
      final mm = d.month.toString().padLeft(2, '0');
      final dd = d.day.toString().padLeft(2, '0');
      data['eventDate'] = '${d.year}-$mm-$dd';
    }

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
