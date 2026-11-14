import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/newbie_task_go.dart';
import 'package:tailtopia/features/profile/domain/newbie_task_labels.dart';
import 'package:tailtopia/features/profile/domain/newbie_tasks.dart';

/// L0：V1.5.0 onboarding-recall Story 3.1 · AC2 ——「去完成」落点表（纯函数）。
void main() {
  NewbieTasks tasks(Set<String> done, {bool hasPet = true, int? reply, int days = 4}) => NewbieTasks(
        items: [for (final k in kNewbieTaskKeys) NewbieTaskItem(key: k, done: done.contains(k))],
        completedCount: done.length,
        total: 7,
        lulusPemulaUnlocked: false,
        hasPet: hasPet,
        oneWeekDaysLeft: days,
        replyGoPostId: reply,
      );
  NewbieTaskItem item(NewbieTasks t, String key) => t.items.firstWhere((i) => i.key == key);

  test('① 有宠物去发布页预选 Diary、无宠物去建档', () {
    final t = tasks({});
    expect(NewbieTaskGo.locationFor(item(t, 'FIRST_DIARY'), t), '/publish?preset=growth-calendar');
    final n = tasks({}, hasPet: false);
    final a = NewbieTaskGo.actionFor(item(n, 'FIRST_DIARY'), n) as NewbieTaskGoButton;
    expect(a.location, '/profile/create');
    expect(a.createProfile, isTrue);
  });

  test('② ① 未完成去发布页、① 已完成只提示', () {
    final t = tasks({});
    expect(NewbieTaskGo.locationFor(item(t, 'FIRST_LIKE_RECEIVED'), t), '/publish?preset=growth-calendar');
    final d = tasks({'FIRST_DIARY'});
    final a = NewbieTaskGo.actionFor(item(d, 'FIRST_LIKE_RECEIVED'), d);
    expect(a, isA<NewbieTaskHint>());
    expect((a as NewbieTaskHint).kind, NewbieTaskHintKind.likeReceived);
  });

  test('③ 推荐宠物列表、④⑤ 社区首页', () {
    final t = tasks({});
    expect(NewbieTaskGo.locationFor(item(t, 'FIRST_FOLLOW'), t), '/pet-recommendations');
    expect(NewbieTaskGo.locationFor(item(t, 'FIRST_LIKE_GIVEN'), t), '/home');
    expect(NewbieTaskGo.locationFor(item(t, 'FIRST_COMMENT'), t), '/home');
  });

  test('⑥ 有人评论过进帖子评论区，否则只提示', () {
    final t = tasks({}, reply: 42);
    expect(NewbieTaskGo.locationFor(item(t, 'FIRST_REPLY'), t), '/content/42?focus=comments');
    final n = tasks({});
    expect(NewbieTaskGo.actionFor(item(n, 'FIRST_REPLY'), n), isA<NewbieTaskHint>());
  });

  test('⑦ 剩余天数、至少 1；完成项无动作；未知 key 无动作', () {
    final t = tasks({}, days: 0);
    expect((NewbieTaskGo.actionFor(item(t, 'ONE_WEEK'), t) as NewbieTaskDaysLeft).days, 1);
    final d = tasks({'FIRST_FOLLOW'});
    expect(NewbieTaskGo.actionFor(item(d, 'FIRST_FOLLOW'), d), isA<NewbieTaskNoAction>());
    expect(NewbieTaskGo.actionFor(const NewbieTaskItem(key: 'X', done: false), d), isA<NewbieTaskNoAction>());
  });

  test('对外序号 1–7', () {
    expect(newbieTaskOrdinal('FIRST_DIARY'), 1);
    expect(newbieTaskOrdinal('ONE_WEEK'), 7);
    expect(newbieTaskOrdinal('CREATE_PROFILE'), isNull);
  });

  test('fromJson 新字段缺省兼容', () {
    final t = NewbieTasks.fromJson({'items': [], 'completedCount': 0, 'total': 7, 'lulusPemulaUnlocked': false});
    expect(t.hasPet, isTrue);
    expect(t.oneWeekDaysLeft, 0);
    expect(t.replyGoPostId, isNull);
    final u = NewbieTasks.fromJson({'items': [], 'hasPet': false, 'oneWeekDaysLeft': 4, 'replyGoPostId': 9});
    expect(u.hasPet, isFalse);
    expect(u.oneWeekDaysLeft, 4);
    expect(u.replyGoPostId, 9);
  });
}
