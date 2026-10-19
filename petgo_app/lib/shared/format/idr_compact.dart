/// 卢比金额缩写（V1.4.0 gift Story 2.2 · AC1 · PRD §3.2、UI 稿「定稿后修订」第 2 条）。
///
/// 规则（**与语言无关** —— 卢比金额一律逗号小数、`rb` / `jt` 后缀，与 `formatIdrAmount` 不随语言变化同口径）：
/// - `amount < 1000`（负数按 0）→ `Rp{amount}`，无单位；
/// - `1000 ≤ amount < 1000000` → 以 1000 为单位，**向下截断**到 1 位小数，小数为 0 不写（`Rp188rb`、`Rp1,5rb`）；
/// - `amount ≥ 1000000` → 以 1000000 为单位，同上（`Rp1,2jt`、`Rp1jt`）。
///
/// 纯整数运算，不用 `double`（不会四舍五入出比实际多的数）。
///
/// 🔴 后端 `petgo-backend/src/main/java/com/tailtopia/shared/format/IdrCompact.java`（Story 2.1）是同规则的另一份实现，
/// 两边测试逐行照抄同一张用例表（对端测试 `petgo-backend/src/test/java/com/tailtopia/shared/format/IdrCompactTest.java`）
/// —— **改一边必须改另一边**。
///
/// ⚠️ 不要拿 `toko_page_v2.dart` 的私有 `_compactIdr` 替代：那是顶栏余额口径（四舍五入、rb 档无小数、无 Rp）。
library;

const int _thousand = 1000;
const int _million = 1000000;

/// 拆开的数字与单位（供「Rp」与单位按 0.6 倍字号分开渲染）：188000 → (`188`, `rb`)；0 → (`0`, ``)。
({String number, String unit}) idrCompactParts(int amount) {
  final a = amount < 0 ? 0 : amount;
  if (a < _thousand) return (number: '$a', unit: '');
  final unit = a < _million ? _thousand : _million;
  final suffix = a < _million ? 'rb' : 'jt';
  // 向下截断到 1 位小数：先放大 10 倍再整除。
  final tenths = a ~/ unit * 10 + (a % unit) * 10 ~/ unit;
  final whole = tenths ~/ 10;
  final frac = tenths % 10;
  return (number: frac == 0 ? '$whole' : '$whole,$frac', unit: suffix);
}

/// 整串，如 `Rp1,5rb`。
String formatIdrCompact(int amount) {
  final p = idrCompactParts(amount);
  return 'Rp${p.number}${p.unit}';
}
