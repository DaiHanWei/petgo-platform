import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/storage/prefs.dart';
import 'profile_prompt_state.dart';

/// 启动期注入的初始提示条状态（main 在加载 prefs + 冷启动 +1 后 override；测试默认 const）。
final Provider<ProfilePromptState> profilePromptBootstrapProvider =
    Provider<ProfilePromptState>((ref) => const ProfilePromptState());

/// 提示条状态管理（Story 1.7 F3）。状态转移走纯函数；持久化 best-effort 写 prefs。
class ProfilePromptController extends Notifier<ProfilePromptState> {
  @override
  ProfilePromptState build() => ref.read(profilePromptBootstrapProvider);

  /// 关闭 X（当次 session 隐藏；第 3 次重启关闭→永久）。
  void dismiss() {
    state = onDismiss(state);
    _persist();
  }

  /// 档案完成 → 永久不显示。
  void markCompleted() {
    state = onProfileCompleted(state);
    _persist();
  }

  Future<void> _persist() async {
    try {
      final prefs = await AppPrefs.create();
      await prefs.setInt(AppPrefs.kProfilePromptRestartCount, state.restartCount);
      await prefs.setBool(AppPrefs.kProfilePromptDismissedPermanently, state.dismissedPermanently);
      await prefs.setBool(AppPrefs.kPetProfileCompleted, state.petProfileCompleted);
    } catch (_) {
      // prefs 不可用（如测试环境未注入）→ 忽略，内存态仍生效。
    }
  }
}

final NotifierProvider<ProfilePromptController, ProfilePromptState> profilePromptProvider =
    NotifierProvider<ProfilePromptController, ProfilePromptState>(ProfilePromptController.new);
