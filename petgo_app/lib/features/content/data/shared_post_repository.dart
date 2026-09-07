import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shared_post.dart';

/// 分享链接落地页的取数（Story 9.3）。
///
/// 🛡 **公开端点、按 token 寻址、未登录可读** —— 要求登录会把人推回浏览器（AC3 同理）。
/// 只有一个方法：**取那一条**。没有"取这只宠物的其它内容"，也不该有。
abstract class SharedPostRepository {
  Future<SharedPost> getSharedPost(String shareToken, {required String fallbackAuthorName});
}

class DioSharedPostRepository implements SharedPostRepository {
  DioSharedPostRepository(this.dio);

  final Dio dio;

  @override
  Future<SharedPost> getSharedPost(String shareToken,
      {required String fallbackAuthorName}) async {
    final resp =
        await dio.get<Map<String, dynamic>>(ApiPaths.publicSharedPost(shareToken));
    return SharedPost.fromJson(resp.data!, fallbackAuthorName: fallbackAuthorName);
  }
}

final Provider<SharedPostRepository> sharedPostRepositoryProvider =
    Provider<SharedPostRepository>((ref) => DioSharedPostRepository(ref.read(dioProvider)));
