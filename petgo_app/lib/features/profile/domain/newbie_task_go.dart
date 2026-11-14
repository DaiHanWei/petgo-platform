import 'newbie_tasks.dart';

/// 一项新手任务右侧的动作（V1.5.0 onboarding-recall Story 3.1 · FR-176「去完成」落点表）。
sealed class NewbieTaskAction {
  const NewbieTaskAction();
}

/// 显示按钮，点了去 [location]；[createProfile] = 按钮文案用「Buat profil」（否则「Yuk!」）。
class NewbieTaskGoButton extends NewbieTaskAction {
  const NewbieTaskGoButton(this.location, {this.createProfile = false});
  final String location;
  final bool createProfile;
}

/// 不放按钮，只显示一行提示（② / ⑥）。
enum NewbieTaskHintKind { likeReceived, reply }

class NewbieTaskHint extends NewbieTaskAction {
  const NewbieTaskHint(this.kind);
  final NewbieTaskHintKind kind;
}

/// 不放按钮，显示「{n} hari lagi!」（⑦）。
class NewbieTaskDaysLeft extends NewbieTaskAction {
  const NewbieTaskDaysLeft(this.days);
  final int days;
}

/// 已完成 / 未知 key：无动作。
class NewbieTaskNoAction extends NewbieTaskAction {
  const NewbieTaskNoAction();
}

/// 「去完成」落点（纯函数，L0 可测）。
///
/// | # | 未完成时 |
/// |---|---|
/// | ① | 有宠物「Yuk!」→ 发布页预选 Diary；无宠物「Buat profil」→ 建档 |
/// | ② | ① 未完成：「Yuk!」→ 发布页；① 已完成：只提示 |
/// | ③ | 「Yuk!」→ 推荐宠物集合页 |
/// | ④⑤ | 「Yuk!」→ 社区首页 |
/// | ⑥ | 有人评论过我的帖子 → 「Yuk!」→ 该帖评论区；否则只提示 |
/// | ⑦ | 「{n} hari lagi!」 |
abstract final class NewbieTaskGo {
  static const String publishDiary = '/publish?preset=growth-calendar';
  static const String createProfile = '/profile/create';
  static const String petRecommendations = '/pet-recommendations';
  static const String home = '/home';

  static NewbieTaskAction actionFor(NewbieTaskItem item, NewbieTasks tasks) {
    if (item.done) return const NewbieTaskNoAction();
    switch (item.key) {
      case 'FIRST_DIARY':
        return tasks.hasPet
            ? const NewbieTaskGoButton(publishDiary)
            : const NewbieTaskGoButton(createProfile, createProfile: true);
      case 'FIRST_LIKE_RECEIVED':
        final diaryDone = tasks.items.any((i) => i.key == 'FIRST_DIARY' && i.done);
        if (diaryDone) return const NewbieTaskHint(NewbieTaskHintKind.likeReceived);
        return tasks.hasPet
            ? const NewbieTaskGoButton(publishDiary)
            : const NewbieTaskGoButton(createProfile, createProfile: true);
      case 'FIRST_FOLLOW':
        return const NewbieTaskGoButton(petRecommendations);
      case 'FIRST_LIKE_GIVEN':
      case 'FIRST_COMMENT':
        return const NewbieTaskGoButton(home);
      case 'FIRST_REPLY':
        final postId = tasks.replyGoPostId;
        return postId == null
            ? const NewbieTaskHint(NewbieTaskHintKind.reply)
            : NewbieTaskGoButton('/content/$postId?focus=comments');
      case 'ONE_WEEK':
        return NewbieTaskDaysLeft(tasks.oneWeekDaysLeft < 1 ? 1 : tasks.oneWeekDaysLeft);
      default:
        return const NewbieTaskNoAction();
    }
  }

  /// 按钮落点（无按钮 → null）。
  static String? locationFor(NewbieTaskItem item, NewbieTasks tasks) {
    final a = actionFor(item, tasks);
    return a is NewbieTaskGoButton ? a.location : null;
  }
}
