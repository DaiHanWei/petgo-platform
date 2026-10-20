import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.4.0 multi-pet · code review 2026-10-18 #2：「我的」页 Hewanku 的 diary 角标 / 性格小标改取
/// `petListProvider`。凡是让「Diary 数」或「佩戴的小标」变了的地方（以前靠失效统计栏 / 档案来刷新），
/// 都必须**同时**失效宠物列表，否则单宠用户在「我的」页看到旧数字 / 旧小标直到重启。
///
/// 守卫做法：扫 `lib/`，凡失效 `archiveStatsProvider` 或在 Tailsonality 页失效 `petProfileProvider` 的文件，
/// 同一文件里必须也有 `ref.invalidate(petListProvider)`。
void main() {
  final invalidateStats = RegExp(r'ref\.invalidate\(archiveStatsProvider\b');
  final invalidateProfile = RegExp(r'ref\.invalidate\(petProfileProvider\)');
  final invalidateList = RegExp(r'ref\.invalidate\(petListProvider\)');

  /// 豁免（带理由）：
  /// - `lib/app.dart`：`resetUserScopedCaches` 换账号时**刻意不** invalidate 宠物列表 —— 列表 watch 当前账号、
  ///   账号一变自己 reload（isReloading），界面靠这个信号在换号期间不渲染上个账号的宠物（code review #4）。
  /// - `tailsonality_quiz_page.dart`：那处失效档案是「档案加载失败 → 重试」，不是小标变化。
  const exempt = {'lib/app.dart', 'lib/features/tailsonality/presentation/tailsonality_quiz_page.dart'};

  List<File> dartFiles() => Directory('lib')
      .listSync(recursive: true)
      .whereType<File>()
      .where((f) => f.path.endsWith('.dart') && !f.path.contains('l10n/app_localizations'))
      .where((f) => !exempt.contains(f.path))
      .toList();

  test('正则自检', () {
    expect(invalidateStats.hasMatch('ref.invalidate(archiveStatsProvider);'), isTrue);
    expect(invalidateStats.hasMatch('ref.invalidate(archiveStatsProvider(widget.petId));'), isTrue);
    expect(invalidateStats.hasMatch('ref.invalidate(archiveStatsProviderFor(x));'), isFalse);
    expect(invalidateList.hasMatch('ref.invalidate(petListProvider);'), isTrue);
  });

  test('失效统计栏的文件都同时失效宠物列表（Diary 数角标）', () {
    final offenders = <String>[
      for (final f in dartFiles())
        if (invalidateStats.hasMatch(f.readAsStringSync()) &&
            !invalidateList.hasMatch(f.readAsStringSync()))
          f.path,
    ];
    expect(offenders, isEmpty);
  });

  test('Tailsonality 佩戴 / 卸下 / 解锁后失效档案的页面也失效宠物列表（性格小标）', () {
    final offenders = <String>[
      for (final f in dartFiles())
        if (f.path.contains('tailsonality/presentation') &&
            invalidateProfile.hasMatch(f.readAsStringSync()) &&
            !invalidateList.hasMatch(f.readAsStringSync()))
          f.path,
    ];
    expect(offenders, isEmpty);
  });
}
