import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import 'sts_credential.dart';

/// 媒体上传数据层（Story 2.1 · F2）。请求 STS 凭证（`POST /media/sts-credentials`）。
/// 抽象便于测试注入 fake。
abstract class MediaRepository {
  Future<StsCredential> requestStsCredential(
    MediaScope scope, {
    String? contentType,
    int count = 1,
  });
}

class DioMediaRepository implements MediaRepository {
  DioMediaRepository(this.dio);

  final Dio dio;

  @override
  Future<StsCredential> requestStsCredential(
    MediaScope scope, {
    String? contentType,
    int count = 1,
  }) async {
    final data = <String, dynamic>{'scope': scope.wire, 'count': count};
    if (contentType != null) {
      data['contentType'] = contentType;
    }
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.mediaStsCredentials, data: data);
    return StsCredential.fromJson(resp.data!);
  }
}

final Provider<MediaRepository> mediaRepositoryProvider =
    Provider<MediaRepository>((ref) => DioMediaRepository(ref.read(dioProvider)));
