import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// V1.3.0 batch-b1 Story 1.8 · L0：推荐 / 不推荐**两处都展示、且只展示**
/// （AC3 / AC4 · B1-D11 · PRD ③）。
///
/// <h2>为什么用扫源码守 AC4</h2>
/// 「不参与排序、不做降权、不做警示标」是一条**越界了看起来反而更像产品升级**的约束：
/// 给差评场所加个小红标、或把它们排到后面，界面上很自然，也不会有任何测试变红 ——
/// 而 PRD ③ 的原话是「先积累数据」。在数据攒起来之前就按评价决定谁被用户看见，
/// 等于用一个没人验证过的阈值做产品决策。
///
/// ⚠️ 真正的视觉验收是 L2；这里守的是"客户端没有偷偷加一层判断"。
void main() {
  String codeOf(String path) => File(path)
      .readAsLinesSync()
      .map((l) => l.trim())
      .where((l) => !l.startsWith('//') && !l.startsWith('///'))
      .join('\n');

  const listPage = 'lib/features/place/presentation/place_list_page.dart';
  const detailPage = 'lib/features/place/presentation/place_detail_page.dart';

  group('🔴 AC3：列表页与详情页都展示两个计数', () {
    test('列表页画了 👍 与 👎', () {
      final code = codeOf(listPage);
      expect(code.contains('place.recommendCount'), isTrue);
      expect(code.contains('place.notRecommendCount'), isTrue);
      expect(code.contains('Icons.thumb_up_outlined'), isTrue);
      expect(code.contains('Icons.thumb_down_outlined'), isTrue);
    });

    test('详情页画了 👍 与 👎', () {
      final code = codeOf(detailPage);
      expect(code.contains('detail.recommendCount'), isTrue);
      expect(code.contains('detail.notRecommendCount'), isTrue);
    });

    /// ⚠️ **不做「为 0 就隐藏」**：一个新场所四个数字都是 0 是正常状态，
    /// 隐藏会让它的卡片比别人矮一截，看起来像加载失败。
    test('计数不按"是否为 0"条件渲染', () {
      for (final path in [listPage, detailPage]) {
        final code = codeOf(path);
        for (final bad in [
          'recommendCount > 0',
          'recommendCount != 0',
          'notRecommendCount > 0',
        ]) {
          expect(code.contains(bad), isFalse,
              reason: '🔴 $path 把计数做成了条件渲染（全 0 是新场所的正常状态）');
        }
      }
    });
  });

  group('🔴 AC4：只展示，不参与排序、不降权、不警示', () {
    test('客户端不按计数重排列表', () {
      final code = codeOf(listPage);
      // 客户端对 items 做任何排序都是越界：顺序由服务端的距离 / 最新分支决定。
      expect(code.contains('.sort('), isFalse,
          reason: '🔴 客户端重排列表 = 服务端那两条排序分支形同虚设（AD-2 Rule 5 / AC4）');
      expect(code.contains('compareTo(') && code.contains('recommendCount'), isFalse);
    });

    test('没有差评警示标 / 降权之类的判断', () {
      for (final path in [listPage, detailPage]) {
        final code = codeOf(path).toLowerCase();
        for (final bad in ['warningbadge', 'lowrated', 'demote', 'notrecommended >']) {
          expect(code.contains(bad), isFalse,
              reason: '🔴 $path 出现了 $bad —— 本版只展示（PRD ③「先积累数据」）');
        }
      }
    });
  });
}
