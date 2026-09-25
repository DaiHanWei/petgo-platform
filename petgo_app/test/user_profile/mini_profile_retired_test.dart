import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// L0：**迷你主页卡零引用 + 每处头像入口都进完整主页**
/// （V1.3.0 batch-b1 Story 2.1 · AC2）。
///
/// ## 为什么值得机械守
/// AC2 是「三处入口逐处收口、一处不漏」。少收一处的表现是：那一处点头像还是弹小卡片 ——
/// **不崩、不报错、测试全绿**，只是这一条路上的用户看到的是上个版本。
/// 人眼复查代价高且会漏（本 story 动手时就发现入口其实是**五处**，Dev Notes 写的三处
/// 是 Epic 1 的场所详情 / 场所评论两处出现之前数的）。
///
/// ⚠️ 加新的「点头像看这人是谁」入口时，把文件加进 [entryPoints]。
/// **不要通过从清单里删掉某个文件来"修"这条测试。**
void main() {
  /// 所有「点头像 / 点作者 → 看这个人是谁」的入口。
  const entryPoints = <String, String>{
    'lib/features/content/presentation/home_page.dart': 'Feed 卡片头像',
    'lib/features/content/presentation/content_detail_page.dart': '内容详情页作者行',
    'lib/features/content/presentation/comment_section.dart': '评论区头像',
    'lib/features/place/presentation/place_detail_page.dart': '场所详情页的标记人（UI 稿 A4）',
    'lib/features/place/presentation/place_comment_section.dart': '场所评论区头像',
  };

  /// 迷你卡组件自己 —— 它**允许**出现 `showMiniProfile`（那是定义处）。
  const miniProfileSheet = 'lib/shared/widgets/mini_profile_sheet.dart';

  test('🔴 迷你卡在 lib/ 里零引用（定义处除外）', () {
    final offenders = <String>[];
    for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
      if (!f.path.endsWith('.dart')) continue;
      if (f.path.endsWith('mini_profile_sheet.dart')) continue;
      if (f.path.contains('public_profile_page.dart')) continue; // 只在注释里提了它
      if (f.readAsStringSync().contains('showMiniProfile(')) offenders.add(f.path);
    }
    expect(
      offenders,
      isEmpty,
      reason: '迷你卡已退役（Story 2.1 AC2）：这些文件还在调它，应改用 openUserProfile。',
    );
  });

  group('每处头像入口都进完整主页', () {
    for (final e in entryPoints.entries) {
      test('${e.value}（${e.key}）', () {
        final src = File(e.key).readAsStringSync();
        expect(src.contains('openUserProfile('), isTrue,
            reason: '${e.value} 没有收口到完整主页');
        expect(src.contains('showMiniProfile('), isFalse,
            reason: '${e.value} 还在弹迷你卡');
      });
    }
  });

  /// 组件文件本身**刻意保留**（它与后端 `/mini-profile` 端点各自还挂着一批既有回归用例）。
  /// 这条测试把「保留」与「退役」的边界钉住：文件在，但头上必须写着它已经退役 ——
  /// 否则下一个人会把它当成一个还在用的公共组件，往上加功能。
  test('迷你卡文件保留，但头部必须标明已退役', () {
    final src = File(miniProfileSheet).readAsStringSync();
    expect(src.contains('已退役'), isTrue,
        reason: '保留一个零引用组件而不标注，等于留了个陷阱');
  });
}
