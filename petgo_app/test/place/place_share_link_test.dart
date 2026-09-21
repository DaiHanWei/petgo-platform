import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';

/// V1.3.0 batch-b1 Story 1.10 · L0：场所分享链接的形态与边界（AC2 / AC8）。
void main() {
  group('🔴 AC2：链接是 /place/{不可枚举 token}', () {
    test('路径形态正确，且与其它三种分享页各走各的', () {
      const token = 'aZ09aZ09aZ09aZ09aZ09aZ09aZ09aZ09';
      expect(placeShareUrl(token), 'https://s.tailtopia.id/place/$token');
      // 四种分享页是四个落点 —— 复用同一个落点就等于把"我只分享这一个"变成别的东西。
      expect(petCardShareUrl(token), 'https://s.tailtopia.id/p/$token');
      expect(milestoneShareUrl(token), 'https://s.tailtopia.id/m/$token');
      expect(postShareUrl(token), 'https://s.tailtopia.id/c/$token');
    });

    test('baseUrl 末尾有没有斜杠都拼得对', () {
      expect(placeShareUrl('t', baseUrl: 'https://x.test/'), 'https://x.test/place/t');
      expect(placeShareUrl('t', baseUrl: 'https://x.test'), 'https://x.test/place/t');
    });

    /// 🔴 **不得用场所名或自增 id 拼链接**（AD-1 Rule 3 / NFR-1）：
    /// 用名字或序号，任何人都能按名字 / 按序号把全站场所爬一遍。
    /// 这里守的是**调用方**那一侧 —— 详情页传进去的必须是 `token`。
    test('详情页传的是 token，不是 name / id', () {
      final src = File('lib/features/place/presentation/place_detail_page.dart')
          .readAsStringSync();
      expect(src.contains('placeShareUrl(p.token)'), isTrue,
          reason: '🔴 传 p.name / 自增 id 进去就是可枚举链接');
    });
  });

  /// 🔴 AC8：**不接入 FR-96 分享奖励** —— 奖励渠道只有年龄卡 / Tailsonality 卡 / 护照卡三个。
  ///
  /// 加进去就是给一条没人批准过的渠道发币，而且不会有任何测试变红。
  test('🔴 AC8：分享场所不触发任何分享奖励', () {
    // 只看代码行 —— 注释里解释"为什么不接"是允许的（那正是这条约束的说明）。
    final src = File('lib/features/place/presentation/place_detail_page.dart')
        .readAsLinesSync()
        .map((l) => l.trim())
        .where((l) => !l.startsWith('//') && !l.startsWith('///'))
        .join('\n');
    for (final banned in [
      'shareReward',
      'ShareReward',
      'pawcoin',
      'PawCoin',
      'idCardShareReward',
    ]) {
      expect(src.contains(banned), isFalse,
          reason: '🔴 场所分享接了奖励（AC8 明确排除）—— 要接先回 FR-96 改口径');
    }
  });

  /// 🔴 AC6 的一半：**App 这一侧**认得的深链 host 与 H5 用的是同一个字面量。
  ///
  /// ⚠️ 另外两半各自在自己那侧守：
  /// - 安卓清单声明 → `test/deeplink_manifest_test.dart`（同在 petgo_app/，随 frontend-ci 跑）；
  /// - 后端模板 / 控制器拼的那个 host → 后端 `PlaceSharePageControllerTest`
  ///   （随 backend-ci 跑）。
  ///
  /// 🔴 **不要在这里读后端文件**（code-review 2026-09-15）：`frontend-ci.yml` 的 `paths:`
  /// 只认 `petgo_app/**` —— 纯后端 PR 改掉那个 host 时，这条测试**根本不会跑**，
  /// 绿灯合入而安卓唤起静默失效。两侧各钉同一个字面量，谁改谁红。
  test('App 侧认得 tailtopia://place/{token}', () {
    expect(deepLinkToLocation(Uri.parse('tailtopia://place/abc')), '/places/abc');
  });
}
