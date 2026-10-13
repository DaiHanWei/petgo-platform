/// SIM / 居住证卡面的编号展示格式（V1.3.4 ktp FR-142E.7，AD-7）。纯展示层：只重排 `card_no` 并加分隔符，
/// 不解码、不改数字，不新增存储字段。只给 SIM / 居住证卡面用（详情预览 / HD 导出 / 分享图是同一组件）；
/// Kartuku 列表与付费抽屉继续原样显示 `card_no`。
///
/// 输入为 14 位身份码 `TT + DDMMYY + SP + XXXX`（DD 已含性别加码）。不符合 `^TT\d{12}$` → 原样返回（防御，不崩）。
library;

final RegExp _cardNoPattern = RegExp(r'^TT(\d{2})(\d{2})(\d{2})(\d{2})(\d{4})$');

/// SIM：`TTDDMM-YYYY-SPXXXX`。4 位年份取卡快照生日年（T-1：`card_no` 年段即生日年）；
/// [birthday] 为 null，或其年份后两位与 `card_no` 的 YY 不一致（不应出现）时按 `20YY` 展开——
/// 卡面编号的年段绝不与 `card_no` 自相矛盾。
String formatSimCardNo(String cardNo, {DateTime? birthday}) {
  final m = _cardNoPattern.firstMatch(cardNo);
  if (m == null) return cardNo;
  final dd = m.group(1)!;
  final mm = m.group(2)!;
  final yy = m.group(3)!;
  final year = (birthday != null && birthday.year % 100 == int.parse(yy))
      ? birthday.year.toString().padLeft(4, '0')
      : '20$yy';
  return 'TT$dd$mm-$year-${m.group(4)}${m.group(5)}';
}

/// 居住证：`TT-DDMMYYSPXXXX`。
String formatKitasCardNo(String cardNo) {
  if (!_cardNoPattern.hasMatch(cardNo)) return cardNo;
  return 'TT-${cardNo.substring(2)}';
}
