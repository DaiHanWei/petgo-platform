import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/retry_policy.dart';
import '../../profile/data/current_pet_provider.dart';
import '../domain/tailsonality_result.dart';
import 'tailsonality_repository.dart';

/// 本人当前宠物的全部 Tailsonality 结果（新 → 旧）。
///
/// `autoDispose`：宠物删档重建 / 换账号后不会沿用旧数据；提交成功后由答题页 `invalidate`。
///
/// V1.4.0 multi-pet：跟随当前宠物（[requestPetIdProvider]）。
final tailsonalityResultsProvider =
    FutureProvider.autoDispose<List<TailsonalityResult>>((ref) async {
  final petId = await ref.watch(requestPetIdProvider.future);
  return ref.read(tailsonalityRepositoryProvider).fetchResults(petId: petId);
});

/// 单次结果（结果页用）。token 不存在或非本人宠物 → 404（以 DioException 抛出）。
/// 404 立即显示「不存在」，不走 Riverpod 默认 15 秒重试（[retryExcept4xx]）。
final tailsonalityResultProvider = FutureProvider.autoDispose.family<TailsonalityResult, String>(
  (ref, token) => ref.read(tailsonalityRepositoryProvider).fetchResult(token),
  retry: retryExcept4xx,
);
