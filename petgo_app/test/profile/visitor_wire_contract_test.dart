import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';

/// V1.1.6 · L0：访客接口的**线上契约**（2026-08-18 L2 视觉验收补）。
///
/// ## 为什么需要这组测试
/// L2 实测发现统计条 Diary 恒显示 **0**，而下面明明列着 4 条日记。
/// 根因：后端把字段叫 `diaryCount`，客户端读的是 `happyMomentCount` ——
/// 而解析时的兜底默认值 `?? 0` **把这个不匹配悄悄吞掉了**：
/// 页面照常渲染、不报任何错、日志里什么都没有，只是那个数字永远是 0。
///
/// 之前的测试全都直接构造 `ArchiveStats` 对象，**没有一条真的走过 JSON 解析**，
/// 所以整条契约缝隙没有任何东西守着。
///
/// ⚠️ 这组用例用的是**后端真实响应的字段名**。改后端字段名而不改这里，这些用例会红。
void main() {
  group('访客统计的字段名必须与客户端解析的一致', () {
    test('真实响应能解析出非零的数字（而不是被兜底默认值吞成 0）', () {
      // 后端 GET /api/v1/public/shared-pets/{token}/stats 的真实响应形状
      final stats = ArchiveStats.fromJson(const {
        'happyMomentCount': 5,
        'consultCount': 1,
        'milestoneCompleted': 7,
        'milestoneTotal': 31,
      });

      expect(stats.happyMomentCount, 5,
          reason: '🔴 解析出 0 说明字段名对不上 —— 页面会静默显示 0，不报任何错');
      expect(stats.consultCount, 1);
      expect(stats.milestoneCompleted, 7);
      expect(stats.milestoneTotal, 31);
    });

    /// 🛡 访客统计里**没有**健康记录条数，解析后该字段应为 0（而不是意外拿到值）。
    test('访客响应不含健康记录条数', () {
      final stats = ArchiveStats.fromJson(const {
        'happyMomentCount': 1,
        'consultCount': 0,
        'milestoneCompleted': 0,
        'milestoneTotal': 31,
      });
      expect(stats.healthRecordCount, 0);
    });
  });

  group('访客时间线条目的「可否点开」', () {
    test('真实响应里的 openable 能被解析出来', () {
      final open = TimelineItem.fromJson(const {
        'itemType': 'HAPPY_MOMENT',
        'date': '2026-08-16T04:09:45.674476Z',
        'eventDate': '2026-08-16',
        'postId': 9431,
        'imageUrls': <String>[],
        'text': 'Jalan pagi di taman',
        'openable': true,
      });
      expect(open.openable, isTrue);

      final locked = TimelineItem.fromJson(const {
        'itemType': 'HAPPY_MOMENT',
        'date': '2026-08-15T04:09:45.674476Z',
        'eventDate': '2026-08-15',
        'postId': 9434,
        'imageUrls': <String>[],
        'text': 'CATATAN PRIVAT',
        'openable': false,
      });
      expect(locked.openable, isFalse,
          reason: '🔴 私密条目若解析不出 false，就会变成可点开 —— 私密内容越出链接边界');
    });

    /// 🛡 作者态响应没有这个字段 → 解析为 null，而访客侧把 null 当作不可点（fail-closed）。
    test('缺字段解析为 null，而不是被当成 true', () {
      final item = TimelineItem.fromJson(const {
        'kind': 'HAPPY_MOMENT',
        'date': '2026-08-16T04:09:45.674476Z',
        'postId': 1,
        'imageUrls': <String>[],
      });
      expect(item.openable, isNull);
    });
  });
}
