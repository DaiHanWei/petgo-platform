import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Story 7.2 J2/J3：双语 key 完全对齐 + microcopy 规则守门（≤1 emoji / 问诊文案无感叹号 / 红色预警简短）。
///
/// 直接解析 `lib/l10n/app_{en,id}.arb`（cwd=包根）。规则以可执行测试固化，不靠人工自觉。
void main() {
  final en = _loadArb('lib/l10n/app_en.arb');
  final id = _loadArb('lib/l10n/app_id.arb');

  test('J2：en / id 两套 key 完全对齐（无缺漏即漏译）', () {
    final enKeys = _messageKeys(en);
    final idKeys = _messageKeys(id);
    expect(enKeys.difference(idKeys), isEmpty, reason: 'id 缺这些 key: ${enKeys.difference(idKeys)}');
    expect(idKeys.difference(enKeys), isEmpty, reason: 'en 缺这些 key: ${idKeys.difference(enKeys)}');
  });

  test('J3：每条文案最多 1 个 emoji（UX-DR14）', () {
    for (final arb in [en, id]) {
      _messageKeys(arb).forEach((k) {
        final v = arb[k];
        if (v is String) {
          expect(_emojiCount(v) <= 1, isTrue, reason: 'key=$k emoji 过多: "$v"');
        }
      });
    }
  });

  test('J3：问诊文案克制——triage* key 不含感叹号（含红色预警无修辞）', () {
    for (final arb in [en, id]) {
      _messageKeys(arb).where((k) => k.startsWith('triage')).forEach((k) {
        final v = arb[k];
        if (v is String) {
          expect(v.contains('!') || v.contains('！'), isFalse, reason: 'key=$k 含感叹号: "$v"');
        }
      });
    }
  });

  /// 🔴 D-3（2026-09-02 stag 电商测试）：**英文基线里混着印尼语**。
  ///
  /// 设备 locale=zh-Hans-CN 时 App 回退英文（app.dart），于是商品详情页显示
  /// "Stok habis" / "Detail Produk"、购物车标题 "Keranjang"、订单 Tab
  /// `All / Konsultasi / PawCoin / Belanja` —— 四个里两个是印尼语。
  /// 全量比对后确认 **38 个键的英文值实为印尼语**，电商域（toko+cart+checkout+order）占 36 个：
  /// 不是零星漏译，是英文基线整体没做完。
  ///
  /// ⚠️ J2 只查「key 有没有对齐」，两边 key 齐全但**值没翻译**它一个字都看不出来 ——
  /// 这正是 38 个键一路绿着上线的原因。本条补上「值有没有真的翻译」。
  ///
  /// <h2>判据</h2>
  /// en 与 id 值完全相同 ⇒ 可疑。但**同形本来就有合理的**：品牌名（PawCoin/QRIS/GoPay）、
  /// 英文借词（Online/Checkout/Refund，印尼语里直接用）、人名、纯格式串。
  /// 所以放行规则是**按词**：值里每个字母词都在 [_sameInBothLocales] 里才放行 ——
  /// 新增一个值为 "PawCoin" 的 key 自动通过，而 "Stok habis" 这种整句印尼语必然变红。
  ///
  /// ⚠️ 往 [_sameInBothLocales] 里加词等于**判定「这个词中英印尼同形」**，是一次决定，
  /// 不是让测试变绿的开关。真要翻译的词加进去，D-3 就会原样再来一次。
  test('🔴 D-3：英文基线不得混入印尼语（en 值与 id 相同者必须是同形词）', () {
    final offenders = <String>[];
    for (final k in _messageKeys(en)) {
      final v = en[k];
      if (v is! String || v != id[k]) continue;
      final foreign = _alphaWords(v).where((w) => !_sameInBothLocales.contains(w.toLowerCase()));
      if (foreign.isNotEmpty) {
        offenders.add('$k = "$v"  ← 未翻译的词: ${foreign.join(", ")}');
      }
    }
    expect(offenders, isEmpty,
        reason: '🔴 这些 key 的英文值与印尼语完全相同，且不是同形词 —— 多半压根没翻译。\n'
            '英文 locale 下用户会看到中英印尼语混排（D-3）。\n${offenders.join("\n")}');
  });

  test('J3：红色预警简短无歧义（triageRed* ≤ 120 字符；就医指引可含必要动作语句）', () {
    for (final arb in [en, id]) {
      _messageKeys(arb).where((k) => k.startsWith('triageRed')).forEach((k) {
        final v = arb[k];
        if (v is String) {
          // 无修辞夸张：长度受限（指引类如「请尽快前往最近动物医院」需完整动作，放宽至 120）。
          expect(v.length <= 120, isTrue, reason: 'key=$k 红色预警过长(${v.length}): "$v"');
        }
      });
    }
  });
}

Map<String, dynamic> _loadArb(String path) =>
    (jsonDecode(File(path).readAsStringSync()) as Map).cast<String, dynamic>();

/// 业务文案 key（排除 @@locale 与 @meta 描述 key）。
Set<String> _messageKeys(Map<String, dynamic> arb) =>
    arb.keys.where((k) => !k.startsWith('@')).toSet();

/// 粗略 emoji 计数：常见 emoji 区块码点。
int _emojiCount(String s) {
  var count = 0;
  for (final r in s.runes) {
    final inEmoji = (r >= 0x1F300 && r <= 0x1FAFF) ||
        (r >= 0x2600 && r <= 0x27BF) ||
        (r >= 0x2B00 && r <= 0x2BFF) ||
        r == 0x2728 ||
        r == 0x2764;
    if (inEmoji) count++;
  }
  return count;
}

/// 值里的字母词（丢掉占位符 `{count}`、数字、标点、CJK）。
List<String> _alphaWords(String v) => v
    .replaceAll(RegExp(r'\{[^}]*\}'), ' ')
    .split(RegExp(r"[^A-Za-z']+"))
    .where((w) => w.isNotEmpty)
    .toList();

/// 英文与印尼语**本来就同形**的词：品牌名 / 专名 / 印尼语直接借用的英文词 / 演示用人名。
///
/// ⚠️ 见上面那条测试的说明：往这里加词是一次判定，不是绿灯开关。
const _sameInBothLocales = {
  // 品牌与产品专名
  'tailtopia', 'pawcoin', 'qris', 'gopay', 'ovo', 'ktp', 'hd',
  // 印尼语直接借用的英文词
  'checkout', 'online', 'offline', 'normal', 'rating', 'refund', 'bonus',
  'edit', 'bug', 'email', 'whatsapp', 'label', 'diary', 'milestone', 'health',
  'major', 'small', 'legend', 'no', 'm', 's', 'l',
  // 语言名（各自的自称，刻意不翻译）
  'english', 'bahasa', 'indonesia',
  // 印尼行政区划专名 —— 2026-09-02 产品拍板：保留原词，
  // 直译成 Province/City/District 会与表单实际层级对不上。
  'provinsi', 'kota', 'kabupaten', 'kecamatan',
  // 演示数据里的人名 / 宠物名
  'aurel', 'mochi',
};
