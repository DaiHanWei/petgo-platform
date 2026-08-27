import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// 建档完成之后的**时机编排**（产品 2026-08-27 改）。
///
/// ## 变更
/// 原先：建档成功 → 庆祝页 → 点主 CTA → **弹推送权限抽屉** → 进 `/home`。
/// 现在：建档成功 → 庆祝页 → 点主 CTA → **直接拉起内容发布**（`/publish`）。
///
/// 两处都是产品决定：
/// - 不弹推送：用户刚填完一整张档案表单、正要去发第一条内容，中间横插一次权限索取 ——
///   那一刻他的意图非常明确，打断它换来的授权率不值得。
/// - 落点改 `/publish`：按钮上写的是「记录第一个瞬间 📸」，原先点完却落在首页信息流，
///   文案承诺的事一件没发生。
///
/// ## ⚠️ 为什么这里是**源码级**断言
/// 本文件的上一版搭了一个「与 app_router 同构」的闭包来跑流程 —— 而它自己的注释就承认过
/// 这个问题：**真实路由改了之后它照样绿，守的是一个已经不存在的结构**。
/// 那种测试比没有更坏（它给人一种被守住了的错觉）。
///
/// 真实路由整体加载进 widget test 需要拉起半个 App 的 provider 图，代价与收益不成比例。
/// 所以这里退一步，直接钉**那两行代码本身**：庆祝页 CTA 里不许再出现推送触发点，
/// 落点必须是 `/publish`。断言弱，但它**真的**盯着会出问题的那一处，
/// 而不是盯着一份复制品。（同一做法在埋点词表测试里已有先例。）
void main() {
  final router = File('lib/core/router/app_router.dart').readAsStringSync();

  /// 取庆祝页 CTA 那个回调的**代码**（已剥掉注释）。
  ///
  /// ⚠️ 必须剥注释：那段回调的注释里如实写着「触发点 2 已下线」并点了
  /// `PushTriggerPoint.profileCreated` 的名字 —— 连注释一起扫会把说明文字当成代码，
  /// 断言直接误报（第一版就是这么红的）。
  String startExploreCode() {
    final lines = router.split('\n');
    final from = lines.indexWhere((l) => l.contains('onStartExplore: () async {'));
    expect(from, greaterThanOrEqualTo(0),
        reason: '🔴 找不到庆祝页主 CTA 的回调 —— 本文件的定位锚点失效了，先修锚点再改断言');
    final out = <String>[];
    for (var i = from; i < lines.length; i++) {
      final raw = lines[i];
      final code = raw.replaceAll(RegExp(r'//.*$'), '').trimRight();
      if (code.isNotEmpty) out.add(code);
      if (raw.trim() == '},' && i > from) break; // 回调闭合
    }
    return out.join('\n');
  }

  test('建档庆祝页的主 CTA 不再弹推送权限（触发点 2 已下线）', () {
    expect(startExploreCode().contains('profileCreated'), isFalse,
        reason: '🔴 建档后又插了一次权限索取 —— 用户正要去发第一条内容，那一刻不该被打断');
  });

  test('建档庆祝页的主 CTA 落到内容发布，而不是首页', () {
    final block = startExploreCode();
    expect(block.contains("'/publish'"), isTrue,
        reason: '🔴 按钮写着「记录第一个瞬间」，落点必须是发布');
    expect(block.contains("go('/home')"), isFalse,
        reason: '🔴 又落回首页了 —— 文案承诺的事一件没发生');
  });

  /// 🛡 枚举值与 prefs 键**刻意保留不删**：键一删，将来若恢复这个时机，
  /// 老用户的「已问过」标记就丢了，会被重新打扰一遍。
  test('触发点枚举与它的 prefs 键仍然保留（供将来恢复，不重复打扰老用户）', () {
    final prompt =
        File('lib/features/notify/domain/push_permission_prompt.dart').readAsStringSync();
    expect(prompt.contains('profileCreated'), isTrue);
    expect(prompt.contains('kPushPromptProfileCreated'), isTrue);
  });
}
