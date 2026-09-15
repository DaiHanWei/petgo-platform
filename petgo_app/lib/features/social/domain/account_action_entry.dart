/// 拉黑 / 举报是**从哪个界面发起的**（V1.1.4，AD-19）。
///
/// ⚠️ 这是「操作入口」，与「产生来源」（BLOCK / REPORT）是**两个维度**，
/// 埋点时分别落在 `entry` 与 `source` 两个 key 上，**别挤进一个 key**。
///
/// 为什么要分入口：黑名单页那个举报入口的点击量，直接验证「拉黑之后仍然需要一个举报入口」
/// 这个设计判断 —— **长期为 0 就说明它可以撤掉**；量不小则说明原来的设计确实堵死了一条路
/// （拉黑后进不去对方主页，而那是举报的唯一入口）。
///
/// **实际上报在 Story 4.1 统一收口**；各 story 只负责把这个参数正确传到发起处。
enum AccountActionEntry {
  /// 用户主页的「···」（Story 1.2 / 2.2；V1.3.0 batch-b1 Story 2.1 起由迷你卡改为整页主页）。
  ///
  /// ⚠️ **上报值仍是 `mini_profile`，不要顺手改成 `profile`**：改版前后在看板上是
  /// **同一条时间序列** —— 换字面量会在改版当天把曲线断成两截，而那天恰好是最需要
  /// 对比改版前后的。入口没变（还是"从这个人的主页发起"），只是主页换了形态。
  miniProfile('mini_profile'),

  /// 黑名单页的行内「⋯」（Story 1.5 / 2.4）。
  blocklist('blocklist'),

  /// 举报流程里自动产生的隐藏关系（Story 2.1，服务端侧）。
  reportFlow('report_flow'),

  /// 评论区点作者（Story 1.6）。
  comment('comment');

  const AccountActionEntry(this.wire);

  /// 埋点属性值（snake_case，与后端/看板口径一致）。
  final String wire;
}

/// 从 [AccountActionEntry.wire] 反解（主页路由的 `?entry=` 参数用）。
///
/// 🛡 **认不出来一律回落 [AccountActionEntry.miniProfile]，绝不抛**：这个参数只喂埋点，
/// 一个手敲错的深链不该让用户看到一屏崩溃。
AccountActionEntry accountActionEntryFromWire(String? wire) {
  for (final e in AccountActionEntry.values) {
    if (e.wire == wire) return e;
  }
  return AccountActionEntry.miniProfile;
}
