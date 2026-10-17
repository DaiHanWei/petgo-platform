import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import 'upload_ticket.dart';

/// 媒体上传数据层（Story 2.1 · F2）。请求预签名上传票据（`POST /media/upload-url`）。
/// 抽象便于测试注入 fake。
abstract class MediaRepository {
  /// [purpose]：可选用途（专题线 video Story 1.6）。`VIDEO_COVER` = 视频封面（服务端签成 private，需等级 ≥ 10）。
  Future<UploadTicket> requestUploadTicket(
    MediaScope scope, {
    String? contentType,
    String? purpose,
  });
}

class DioMediaRepository implements MediaRepository {
  DioMediaRepository(this.dio);

  final Dio dio;

  @override
  Future<UploadTicket> requestUploadTicket(
    MediaScope scope, {
    String? contentType,
    String? purpose,
  }) async {
    final data = <String, dynamic>{'scope': scope.wire};
    if (contentType != null) {
      data['contentType'] = contentType;
    }
    // 不带用途时整键不出现（与老请求体逐字节一致）。
    if (purpose != null) {
      data['purpose'] = purpose;
    }
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.mediaUploadUrl, data: data);
    return UploadTicket.fromJson(resp.data!);
  }
}

final Provider<MediaRepository> mediaRepositoryProvider =
    Provider<MediaRepository>((ref) => DioMediaRepository(ref.read(dioProvider)));
