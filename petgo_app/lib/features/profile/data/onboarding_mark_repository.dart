import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 一次性引导标记（V1.3.0 批次 A · Story 5.4 · AD-A21）。
///
/// 🔴 **按账号存，不按设备**。这与 v1.1.6 的推送权限引导（走本地 prefs、按设备）不同：
/// 那件事的判断依据「系统通知开关」本身就是设备级状态；而「KTP 挪位置了」是一次
/// **认知性告知**，同一个人换设备后并不需要被再告知一次。
///
/// ⚠️ 代价已称重：从本地 prefs 变成「服务端表 + 读写接口」是一次范式变化 ——
/// 多两个端点、多一次冷启动读、且**离线首启读不到标记**（届时按「未看过」处理，
/// 可能多弹一次，可接受）。
class OnboardingMarkRepository {
  OnboardingMarkRepository(this._ref);

  final Ref _ref;

  Future<Set<String>> fetchMarks() async {
    final dio = _ref.read(dioProvider);
    final res = await dio.get<Map<String, dynamic>>(ApiPaths.meOnboardingMarks);
    final marks = res.data?['marks'];
    if (marks is! List) return const <String>{};
    return marks.whereType<String>().toSet();
  }

  /// 置位一个键。服务端幂等（唯一约束兜底），重复调用不会出第二行。
  Future<void> mark(String key) async {
    final dio = _ref.read(dioProvider);
    await dio.post<void>(ApiPaths.meOnboardingMarks, data: {'key': key});
  }
}

final onboardingMarkRepositoryProvider =
    Provider<OnboardingMarkRepository>(OnboardingMarkRepository.new);

/// 已置位的键集合。
///
/// 🛡 **读不到一律按「未看过」处理**（AC4）：离线首启、接口失败都走这条 ——
/// 多弹一次是已接受的代价，而"读失败就当看过"会让引导对一批人**永远不出现**。
final onboardingMarksProvider = FutureProvider<Set<String>>((ref) async {
  try {
    return await ref.read(onboardingMarkRepositoryProvider).fetchMarks();
  } catch (_) {
    return const <String>{};
  }
});

/// 本批次唯一的键（AC6）。批次 C 的性格测试引导**必须另起一个键**，禁止共用 ——
/// 共用会让看过第一次的人再也收不到第二次。
const String kOnboardingMarkKtpMoved = 'ktp_moved';
