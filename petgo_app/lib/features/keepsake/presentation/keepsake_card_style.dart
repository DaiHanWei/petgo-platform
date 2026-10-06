/// 付费保护卡（Tailsonality 结果卡 / 护照 / 登机牌）未解锁时的水印浓度（待确认 4.1，2026-10-02）。
///
/// 与 KTP 一致取 0.45：KTP 曾因「免费图和付费高清图看着没区别」从 0.25 调到 0.45；付费保护卡水印太淡等于白送。
/// 页内卡、大图、发帖图、分享卡预览与导出都用这一个值。配型卡与帖子卡永不带水印，不涉及。
const double kKeepsakeWatermarkOpacity = 0.45;

/// 四类卡（结果 / 配型 / 护照 / 登机牌）分享预览「出图成功」埋点（待确认 4.3，2026-10-02）。
///
/// 对标帖子卡 `post_share_card_generated`：用来算「出了图但没分享」的流失。`card_type` ∈ result / match / page / boarding。
/// 不带宠物名 / token / 护照号。
const String kKeepsakeCardGeneratedEvent = 'keepsake_card_generated';
