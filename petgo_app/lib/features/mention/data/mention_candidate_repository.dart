import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// @ 候选集里的一个人（V1.3.0 batch-b1 Story 3.2 · AC2）。
///
/// 🔴 [userId] 才是被存下来的那一份身份（AC4 / AD-10 Rule 4）。[nickname] 只用来
/// **这一刻显示在选择器里、以及插进正文让人读** —— 它随时会过期（对方改名），
/// 所以提交时只发 id，渲染时按 id 实时查（Story 3.3）。
class MentionCandidate {
  const MentionCandidate({required this.userId, required this.nickname, this.avatarUrl});

  final int userId;
  final String nickname;
  final String? avatarUrl;

  factory MentionCandidate.fromJson(Map<String, dynamic> json) => MentionCandidate(
        userId: json['userId'] as int,
        nickname: (json['nickname'] as String?) ?? '',
        avatarUrl: json['avatarUrl'] as String?,
      );
}

abstract class MentionCandidateRepository {
  /// 取最近互动过的人（最多 30）。
  ///
  /// ⚠️ **不接受任何关键词参数** —— 没有全局用户搜索（Story 3.1 AC5）。
  Future<List<MentionCandidate>> getCandidates();
}

class DioMentionCandidateRepository implements MentionCandidateRepository {
  DioMentionCandidateRepository(this.dio);

  final Dio dio;

  @override
  Future<List<MentionCandidate>> getCandidates() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meMentionCandidates);
    final items = (resp.data?['items'] as List<dynamic>? ?? const <dynamic>[]);
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
/// ⚠️ 关掉自动重试：候选集取不到时选择器显示的是空态（AC3 同一个空态），
/// 让它在后台反复重试只会让浮层在"空态 ↔ 转圈"之间自己横跳，而用户正在打字。
/// （同 Story 2.1 的 publicProfileProvider。）
final FutureProvider<List<MentionCandidate>> mentionCandidatesProvider =
    FutureProvider<List<MentionCandidate>>(
  (ref) => ref.read(mentionCandidateRepositoryProvider).getCandidates(),
  retry: (_, _) => null,
);
