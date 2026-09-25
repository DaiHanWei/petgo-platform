/// 所有可上报按钮的唯一来源（埋点治理 P0，2026-07-27 工程补丁 §1）。
///
/// 核心原则：**标签只能来自这里的常量，永远不从 UI 文本反推**。新增按钮必须先在此登记，
/// 并同步加入 `Analytics` 的白名单；任何未登记的点击一律不上报——不做兜底、不猜标签。
abstract final class ButtonId {
  static const triageStart = 'triage.start';
  static const triageUpload = 'triage.upload';
  static const consultStart = 'consult.start';
  static const publishSubmit = 'publish.submit';
  static const profileCreate = 'profile.create';
  static const milestoneShare = 'milestone.share';
  static const vetAcceptQueue = 'vet.accept_queue';
  static const vetAdviceTemplate = 'vet.advice_template';

  /// 客服 WhatsApp 深链入口（V1.3.0 Story 3-3，§5 指标「WhatsApp 深链点击数（按周）」）。
  /// 🔴 加了这行还**必须**同步加进 `Analytics._allowedButtonIds` —— 只加常量的话
  /// `buttonTapped` 会在 release 下被 `isRegisteredButtonId` **静默丢弃**，
  /// 表现是「开发机上有数据、线上永远零」，发版后才发现，还要等下一个版本才能修。
  static const supportWhatsapp = 'support.whatsapp';
}
