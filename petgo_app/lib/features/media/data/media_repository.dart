import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import 'upload_ticket.dart';

/// 媒体上传数据层（Story 2.1 · F2）。请求预签名上传票据（`POST /media/upload-url`）。
/// 抽象便于测试注入 fake。
abstract class MediaRepository {
  Future<UploadTicket> requestUploadTicket(
    MediaScope scope, {
    String? contentType,
  });
}

class DioMediaRepository implements MediaRepository {
  DioMediaRepository(this.dio);

  final Dio dio;

  @override
  Future<UploadTicket> requestUploadTicket(
    MediaScope scope, {
    String? contentType,
  }) async {
    final data = <String, dynamic>{'scope': scope.wire};
    if (contentType != null) {
      data['contentType'] = contentType;
    }
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.mediaUploadUrl, data: data);
    return UploadTicket.fromJson(resp.data!);
  }
}

final Provider<MediaRepository> mediaRepositoryProvider =
    Provider<MediaRepository>((ref) => DioMediaRepository(ref.read(dioProvider)));
