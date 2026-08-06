import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/shared/widgets/app_shell.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

/// L0：底栏「＋」按 Tab 语境预选发布类型。
///
/// 回归的缺陷（2026-08-05 用户反馈）：在 Social（内容流）点「＋」开在 Diary 上 ——
/// 原实现只对 Diary Tab 给 preset，其余一律 null，于是有宠物档案的用户被发布页按
/// 「有档案 → Diary」回落，把明显想发广场动态的意图带偏。
void main() {
  test('Social Tab → 预选 Moment（有无档案都一样，语境优先）', () {
    expect(addButtonPreset(AppTab.home, canGrowth: true), ContentType.daily);
    expect(addButtonPreset(AppTab.home, canGrowth: false), ContentType.daily);
  });

  test('Diary Tab + 有档案 → 预选 Diary', () {
    expect(addButtonPreset(AppTab.profile, canGrowth: true), ContentType.growthMoment);
  });

  test('Diary Tab + 无档案 → 不预选（segment 灰置，交给发布页回落 Moment）', () {
    expect(addButtonPreset(AppTab.profile, canGrowth: false), isNull);
  });

  test('Health / Me 无语境 → 不预选，判定留给发布页', () {
    for (final tab in [AppTab.triage, AppTab.me]) {
      expect(addButtonPreset(tab, canGrowth: true), isNull, reason: '$tab 不该抢发布页的默认值判定');
      expect(addButtonPreset(tab, canGrowth: false), isNull);
    }
  });
}
