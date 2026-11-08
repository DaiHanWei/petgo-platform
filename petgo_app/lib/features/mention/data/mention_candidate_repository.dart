import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/mention_relation.dart';

/// @ 候选集里的一个人（V1.3.0 batch-b1 Story 3.2 · AC2）。
///
/// 🔴 [userId] 才是被存下来的那一份身份（AC4 / AD-10 Rule 4）。[nickname] 只用来
/// **这一刻显示在选择器里、以及插进正文让人读** —— 它随时会过期（对方改名），
/// 所以提交时只发 id，渲染时按 id 实时查（Story 3.3）。
class MentionCandidate {
  const MentionCandidate({
    required this.userId,
    required this.nickname,
    this.avatarUrl,
    this.relation = MentionRelation.interacted,
  });

  final int userId;
  final String nickname;
  final String? avatarUrl;

  /// 与我的关系（V1.4.2 Story 5.1 / 5.2）：只用于埋点 `mention_inserted.relation`，界面不展示。
  final MentionRelation relation;

  factory MentionCandidate.fromJson(Map<String, dynamic> json) => MentionCandidate(
        userId: json['userId'] as int,
        nickname: (json['nickname'] as String?) ?? '',
        avatarUrl: json['avatarUrl'] as String?,
        relation: MentionRelation.parse(json['relation'] as String?),
      );
}

/// @ 候选（V1.4.2 Story 5.1 / 5.2）：**只在关注 / 粉丝 / 互动三类范围内查，不是全局搜索**。
/// 排序与范围是服务端规则，App 原样按接口顺序渲染。
abstract class MentionCandidateRepository {
  /// 快捷列表（`@` 后无字时，最多 30）。
  Future<List<MentionCandidate>> getCandidates();

  /// 按 `@` 后的字在三类范围内查（`?q=`，最多 20）。超频 429 / 超长 422 → 抛 [DioException]。
  Future<List<MentionCandidate>> search(String keyword, {CancelToken? cancelToken});
}

class DioMentionCandidateRepository implements MentionCandidateRepository {
  DioMentionCandidateRepository(this.dio);

  final Dio dio;

  @override
  Future<List<MentionCandidate>> getCandidates() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meMentionCandidates);
    return _parse(resp.data);
  }

  @override
  Future<List<MentionCandidate>> search(String keyword, {CancelToken? cancelToken}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.meMentionCandidates,
      queryParameters: <String, dynamic>{'q': keyword},
      cancelToken: cancelToken,
    );
    return _parse(resp.data);
  }

  static List<MentionCandidate> _parse(Map<String, dynamic>? data) {
    final items = (data?['items'] as List<dynamic>? ?? const <dynamic>[]);
    return items
        .map((e) => MentionCandidate.fromJson(e as Map<String, dynamic>))
        .toList(growable: false);
  }
}

final Provider<MentionCandidateRepository> mentionCandidateRepositoryProvider =
    Provider<MentionCandidateRepository>(
        (ref) => DioMentionCandidateRepository(ref.read(dioProvider)));

/// @ 候选集（Story 3.2 AC2/AC3）。
///
/// ⚠️ 关掉自动重试：取不到时面板显示 E5（失败 + Coba lagi，V1.4.2 Story 5.2），由用户点重试；
/// 让它在后台反复重试只会让面板在"失败 ↔ 转圈"之间自己横跳，而用户正在打字。
/// （同 Story 2.1 的 publicProfileProvider。）
///
/// 🔴 **autoDispose**（batch-b1 复审）：这是按当前用户算的「最近互动的人」。常驻整个进程的话：
/// 换账号后 B 看到 A 的联系人（隐私泄漏，且选中后被服务端按 B 的候选集静默丢弃）；
/// 之后新互动的人要重启才出现；首次取数失败则整个会话都是空态。
/// 浮层每次弹出重新取一次（30 人以内的小列表），关掉即回收。另已登记 `resetUserScopedCaches`。
final mentionCandidatesProvider = FutureProvider.autoDispose<List<MentionCandidate>>(
  (ref) => ref.read(mentionCandidateRepositoryProvider).getCandidates(),
  retry: (_, _) => null,
);
