import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/presentation/health_list_page.dart';

MilestoneItem _mi(String code, {required bool completed}) => MilestoneItem.fromJson({
      'code': code,
      'title': code,
      'level': 'S',
      'triggerType': 'SYSTEM_AUTO',
      'completed': completed,
    });

MilestoneList _list(List<MilestoneItem> items) => MilestoneList.fromJson({
      'petName': 'Mochi',
      'groups': [
        {
          'level': 'S',
          'items': [
            for (final it in items)
              {
                'code': it.code,
                'title': it.code,
                'level': 'S',
                'triggerType': 'SYSTEM_AUTO',
                'completed': it.completed,
              },
          ],
        },
      ],
    });

void main() {
  // bug 20260729-405：保存健康记录后是否需要轮询检测新解锁（候选判定纯函数）。
  group('mayUnlockHealthMilestone', () {
    test('VACCINE 且 M3 未完成 → 有候选', () {
      final list = _list([_mi('C-M3', completed: false)]);
      expect(mayUnlockHealthMilestone('VACCINE', list), isTrue);
    });

    test('VACCINE 但 M3 已完成 → 无候选（不白等轮询）', () {
      final list = _list([_mi('C-M3', completed: true)]);
      expect(mayUnlockHealthMilestone('VACCINE', list), isFalse);
    });

    test('DEWORM 对应 M4', () {
      expect(mayUnlockHealthMilestone('DEWORM', _list([_mi('D-M4', completed: false)])), isTrue);
      expect(mayUnlockHealthMilestone('DEWORM', _list([_mi('D-M4', completed: true)])), isFalse);
    });

    test('NEUTER 无直接节点；S1–S5 已齐且 Lulus Pemula 未完成 → 走聚合候选', () {
      final sDone = [for (final s in ['S1', 'S2', 'S3', 'S4', 'S5']) _mi('C-$s', completed: true)];
      expect(
          mayUnlockHealthMilestone(
              'NEUTER', _list([...sDone, _mi('C-S16', completed: false)])),
          isTrue);
      // S 前置未齐 → 无候选。
      expect(
          mayUnlockHealthMilestone(
              'NEUTER',
              _list([
                ...sDone.sublist(0, 4),
                _mi('C-S5', completed: false),
                _mi('C-S16', completed: false),
              ])),
          isFalse);
    });

    test('OTHER 宠物 Lulus Pemula 后缀 S9 同样命中', () {
      final sDone = [for (final s in ['S1', 'S2', 'S3', 'S4', 'S5']) _mi('G-$s', completed: true)];
      expect(
          mayUnlockHealthMilestone(
              'CUSTOM', _list([...sDone, _mi('G-S9', completed: false)])),
          isTrue);
    });
  });
}
