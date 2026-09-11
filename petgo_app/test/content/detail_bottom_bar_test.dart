import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/detail_bottom_bar.dart';

/// V1.3.0 批次 A · Story 2.3（L0）：详情页固定底栏的两态规则与度量（FR-114 · AD-A13）。
///
/// UI 稿 D1 的原话：这「比"挪位置"更深的交互状态变化」。规则不定死，两个实现会对
/// 「打字时点赞按钮还在不在」给出不同答案 —— 所以判定被抽成纯函数放 domain 层，在这里钉住。
///
/// 键盘弹起时的避让、以及避让过程中的切换时序只能真机验（见 story 的 L2 清单）；
/// 本类只管**规则本身**。
void main() {
  group('AC2 🔴 两态互斥', () {
    test('未聚焦 + 无输入 → 点赞 / 分享（默认态）', () {
      expect(
        resolveBottomBarMode(focused: false, hasText: false),
        DetailBottomBarMode.actions,
      );
    });

    /// 「或」而不是「且」：用户点了输入框还没打字，此刻他显然要评论，
    /// 这时候还摆着点赞分享就是干扰。
    test('已聚焦但还没打字 → 发送', () {
      expect(
        resolveBottomBarMode(focused: true, hasText: false),
        DetailBottomBarMode.compose,
      );
    });

    /// 反方向同理：输入框有草稿但焦点被别处抢走时，发送键必须还在 ——
    /// 否则他打的字没地方交。
    test('有草稿但失焦 → 仍是发送', () {
      expect(
        resolveBottomBarMode(focused: false, hasText: true),
        DetailBottomBarMode.compose,
      );
    });

    test('聚焦且有输入 → 发送', () {
      expect(
        resolveBottomBarMode(focused: true, hasText: true),
        DetailBottomBarMode.compose,
      );
    });

    /// 🔴 「同一时刻只存在一组，无第三种组合」—— 这条在类型上就成立：
    /// 枚举只有两个值，四种输入组合全部落进这两个值之一。
    /// 若日后有人把它改成两个 bool，本断言会立刻失去意义，所以连枚举本身一起钉。
    test('值域恰好两个，四种输入组合全覆盖且无遗漏', () {
      expect(DetailBottomBarMode.values, hasLength(2));

      final seen = <DetailBottomBarMode>{};
      for (final focused in [true, false]) {
        for (final hasText in [true, false]) {
          seen.add(resolveBottomBarMode(focused: focused, hasText: hasText));
        }
      }
      expect(seen, DetailBottomBarMode.values.toSet());
    });
  });

  group('AC4 图标度量与热区', () {
    test('可见尺寸 19、间距 22', () {
      expect(DetailBarMetrics.iconSize, 19);
      expect(DetailBarMetrics.iconGap, 22);
    });

    /// 🔴 热区是**隐性扩展**：命中框 ≥44，可见图标仍是 19。
    /// 两者相等就说明有人把图标画大了 —— 那改变的是设计稿的视觉密度，不是可达性。
    test('热区 ≥44 且与可见尺寸不是同一个数', () {
      expect(DetailBarMetrics.minTapTarget, greaterThanOrEqualTo(44));
      expect(
        DetailBarMetrics.minTapTarget,
        isNot(DetailBarMetrics.iconSize),
        reason: '热区扩展必须是隐性的 —— 扩命中框，不是把图标画大',
      );
    });
  });
}
