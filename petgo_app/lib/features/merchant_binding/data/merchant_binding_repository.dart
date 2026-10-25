import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/problem_detail.dart';

/// 当前用户与门店的绑定（V1.4.1 商家平台对接 FR-7）。一个账号只能绑一家店，绑后不能更换。
class MerchantBinding {
  const MerchantBinding({required this.bound, this.inviteCode, this.boundAt});

  final bool bound;

  /// 绑定门店的邀请码（6 位数字）。门店记录被删时后端给 null。
  final String? inviteCode;
  final DateTime? boundAt;

  static const MerchantBinding none = MerchantBinding(bound: false);

  factory MerchantBinding.fromJson(Map<String, dynamic> j) => MerchantBinding(
        bound: j['bound'] == true,
        inviteCode: j['inviteCode'] as String?,
        boundAt: j['boundAt'] == null ? null : DateTime.tryParse(j['boundAt'] as String),
      );
}

/// 绑定失败的分类（按后端 ProblemDetail `type` 末段，App 不渲染后端文案）。
enum MerchantBindError { invalidCode, alreadyBound, rateLimited, network }

class MerchantBindException implements Exception {
  const MerchantBindException(this.error);

  final MerchantBindError error;
}

abstract class MerchantBindingRepository {
  Future<MerchantBinding> current();

  /// 绑定。失败抛 [MerchantBindException]。
  Future<MerchantBinding> bind(String code);
}

class DioMerchantBindingRepository implements MerchantBindingRepository {
  DioMerchantBindingRepository(this.dio);

  final Dio dio;

  @override
  Future<MerchantBinding> current() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meMerchantBinding);
    return MerchantBinding.fromJson(resp.data ?? const <String, dynamic>{});
  }

  @override
  Future<MerchantBinding> bind(String code) async {
    try {
      final resp = await dio.post<Map<String, dynamic>>(ApiPaths.meMerchantBinding,
          data: <String, dynamic>{'code': code});
      return MerchantBinding.fromJson(resp.data ?? const <String, dynamic>{});
    } on DioException catch (e) {
      final pd = ProblemDetail.fromDioException(e);
      final error = switch ((pd?.status, pd?.typeSlug)) {
        (_, 'merchant-code-invalid') => MerchantBindError.invalidCode,
        (_, 'merchant-already-bound') => MerchantBindError.alreadyBound,
        (429, _) => MerchantBindError.rateLimited,
        _ => MerchantBindError.network,
      };
      throw MerchantBindException(error);
    }
  }
}

final Provider<MerchantBindingRepository> merchantBindingRepositoryProvider =
    Provider<MerchantBindingRepository>((ref) => DioMerchantBindingRepository(ref.read(dioProvider)));

/// 设置页入口右值与填码页共用。失败停在错误态（关掉 Riverpod 3 自动重试，同 `blockedUsersProvider`）。
final merchantBindingProvider = FutureProvider.autoDispose<MerchantBinding>(
  (ref) => ref.read(merchantBindingRepositoryProvider).current(),
  retry: (_, _) => null,
);
