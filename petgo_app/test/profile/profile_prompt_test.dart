import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/domain/profile_prompt_state.dart';

void main() {
  ProfilePromptState s({
    int restart = 0,
    bool permanent = false,
    bool completed = false,
    bool session = false,
  }) =>
      ProfilePromptState(
        restartCount: restart,
        dismissedPermanently: permanent,
        petProfileCompleted: completed,
        dismissedThisSession: session,
      );

  bool show(String? petStatus, ProfilePromptState state, {bool hasPetProfile = false}) =>
      shouldShowProfilePrompt(petStatus: petStatus, hasPetProfile: hasPetProfile, state: state);

  group('FR-0H 提示条状态机（纯函数）', () {
    test('A 用户、未创建、第 1/2/3 次重启 → 显示；第 4 次（>3）→ 不显示', () {
      expect(show('HAS_PET', s(restart: 1)), isTrue);
      expect(show('HAS_PET', s(restart: 2)), isTrue);
      expect(show('HAS_PET', s(restart: 3)), isTrue);
      expect(show('HAS_PET', s(restart: 4)), isFalse);
    });

    test('当次关闭（count<3）→ 本 session 不显示、计数不增、下次重启仍显示', () {
      final dismissed = onDismiss(s(restart: 1));
      expect(dismissed.dismissedThisSession, isTrue);
      expect(dismissed.dismissedPermanently, isFalse);
      expect(dismissed.restartCount, 1); // 计数不增
      expect(show('HAS_PET', dismissed), isFalse); // 本 session 不显示

      // 下次重启：dismissedThisSession 重置（内存态不持久）+ 计数 +1
      final nextLaunch = onColdStartIncrement(s(restart: 1));
      expect(nextLaunch.restartCount, 2);
      expect(show('HAS_PET', nextLaunch), isTrue);
    });

    test('第 3 次重启关闭 → 永久关闭，之后任何重启不显示', () {
      final dismissed = onDismiss(s(restart: 3));
      expect(dismissed.dismissedPermanently, isTrue);
      expect(show('HAS_PET', dismissed), isFalse);
      // 后续重启即使计数仍 ≤3 也不显示
      expect(show('HAS_PET', s(restart: 2, permanent: true)), isFalse);
    });

    test('任意时刻 petProfileCompleted → 不显示', () {
      expect(show('HAS_PET', s(restart: 1, completed: true)), isFalse);
      expect(show('HAS_PET', onProfileCompleted(s(restart: 1))), isFalse);
    });

    test('hasPetProfile（Epic 2 回填 true）→ 即使计数未满也不显示', () {
      expect(show('HAS_PET', s(restart: 1), hasPetProfile: true), isFalse);
    });

    test('B/C 用户 → 任何情况都不显示（计数不激活）', () {
      expect(show('PLANNING', s(restart: 1)), isFalse);
      expect(show('ENTHUSIAST', s(restart: 1)), isFalse);
      expect(show('PLANNING', s(restart: 3)), isFalse);
      expect(show(null, s(restart: 1)), isFalse); // 未登录/无状态
    });

    test('冷启动计数封顶到 4（避免无限增长）', () {
      expect(onColdStartIncrement(s(restart: 2)).restartCount, 3);
      expect(onColdStartIncrement(s(restart: 4)).restartCount, 4);
    });
  });
}
