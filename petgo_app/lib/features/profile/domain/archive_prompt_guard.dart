import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/health_event_repository.dart';

/// 存档弹窗「只问一次」守卫（Story 2.5 · AC1）。
///
/// 决策依据：本地已处理标记（防抖）+ 后端 `hasDecision`（持久幂等）。两者任一已决策 → 不再弹。
class ArchivePromptGuard {
  ArchivePromptGuard(this._repo);

  final HealthEventRepository _repo;
  final Set<String> _handledLocally = <String>{};

  /// 该问诊是否仍需弹存档窗。
  Future<bool> needsPrompt(String sourceRef) async {
    if (_handledLocally.contains(sourceRef)) return false;
    final decided = await _repo.hasDecision(sourceRef);
    if (decided) {
      _handledLocally.add(sourceRef);
      return false;
    }
    return true;
  }

  /// 用户已做决策后置位，本 session 内不再弹。
  void markHandled(String sourceRef) => _handledLocally.add(sourceRef);
}

final Provider<ArchivePromptGuard> archivePromptGuardProvider =
    Provider<ArchivePromptGuard>((ref) => ArchivePromptGuard(ref.read(healthEventRepositoryProvider)));
