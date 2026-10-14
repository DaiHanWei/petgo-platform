import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';

/// V1.3.4 Story 4.3 测试共用：当场庆祝的假里程碑仓库与清单构造（非 `_test` 文件，不单独跑）。

/// 只含一条 [code] 的单宠清单。
MilestoneList instantMilestoneList(String code,
        {required bool completed, bool celebrated = false, String petName = 'Momo'}) =>
    MilestoneList(
      petName: petName,
      completedCount: completed ? 1 : 0,
      totalCount: 1,
      groups: [
        MilestoneGroup(
          level: MilestoneLevel.s,
          completedCount: completed ? 1 : 0,
          totalCount: 1,
          items: [
            MilestoneItem(
              code: code,
              title: code,
              level: MilestoneLevel.s,
              trigger: MilestoneTrigger.systemAuto,
              completed: completed,
              completedAt: completed ? DateTime(2026, 10, 12) : null,
              celebratedAt: celebrated ? DateTime(2026, 10, 12) : null,
            ),
          ],
        ),
      ],
    );

/// 按调用次序依次返回 [script] 里的值（超出取最后一个）；值为 `Future<MilestoneList>` 时原样返回
/// （可传永不完成的 future 测超时），为 [Exception] / [Error] 时抛出。
class ScriptedMilestoneRepository implements MilestoneRepository {
  ScriptedMilestoneRepository(this.script);

  final List<Object> script;
  int calls = 0;
  final reported = <List<String>>[];

  @override
  Future<MilestoneList> getMilestones() {
    final v = script[calls < script.length ? calls : script.length - 1];
    calls++;
    if (v is MilestoneList) return Future.value(v);
    if (v is Future<MilestoneList>) return v;
    return Future.error(v);
  }

  @override
  Future<void> reportCelebrations(List<String> codes) async {
    reported.add(codes);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
