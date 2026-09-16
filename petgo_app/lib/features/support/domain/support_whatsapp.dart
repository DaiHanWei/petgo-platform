/// 客服 WhatsApp 深链（Story 3-3 / AD-S8 / SHOP-NFR-05）。
///
/// 两个纯函数 + 一条红线：**预填串里只有订单号和一句固定引导语，别的什么都不许有**。
library;

import '../../../l10n/app_localizations.dart';

/// 构造 WhatsApp 预填文案。
///
/// 🔒🔒 **SHOP-NFR-05 红线：这里只拼 [orderNo]。**
/// 收件人姓名 / 电话 / 详细地址 / 邮政编码 / 用户 id —— 一个都不许进来。
/// 深链的 `text` 会落进用户的 WhatsApp 输入框，也就落进了对话记录、
/// 落进了他可能转发给别人的截图。订单号是他自己的、也是客服要的那一个；
/// 其余每一项都只是「顺手带上」，而「顺手带上」正是 PII 泄漏的标准形状。
///
/// ⚠️ 本函数**刻意只收一个 `String orderNo` 而不是整个订单对象** ——
/// 收对象的话，往里加一句「送到 {address}」就只是一行代码的事。
/// 签名本身就是护栏：`test/support/support_whatsapp_test.dart` 有专门的变异验证。
String buildSupportWhatsAppText({
  required String orderNo,
  required AppLocalizations l10n,
}) {
  return l10n.supportWhatsappPrefill(orderNo);
}

/// 构造 `wa.me` 深链。
///
/// 🔴 **路径段不要 `+`**：provider 给的是标准 E.164（`+6281290906953`），
/// 而 `wa.me` 的路径要的是 `6281290906953`。剥 `+` 是 URL 构造方的事 ——
/// 让 provider 输出一个非标准值去迁就某一个消费方，下一个消费方就得反过来拼回去。
Uri buildSupportWhatsAppUri({
  required String whatsappE164,
  required String text,
}) {
  final digits = whatsappE164.startsWith('+')
      ? whatsappE164.substring(1)
      : whatsappE164;
  // Uri.https 会自行对 query 做百分号编码，不必也不该再手工 encode 一遍（会双重编码）。
  return Uri.https('wa.me', '/$digits', {'text': text});
}
