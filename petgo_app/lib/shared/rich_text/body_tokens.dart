/// 正文标记解析器：话题 `#名字` 与 @ 定位（V1.4.2 social-topic · Story 4.4 · P-1 / D-19）。
///
/// <h3>🔴 话题名规则在 App 侧只有这一份实现</h3>
/// 与后端 `TopicNameRules`（`petgo-backend/.../topic/domain/TopicNameRules.java`）**逐条一致**，
/// 两边测试读同一份样例 `petgo-backend/src/test/resources/fixtures/topic-parse-cases.json`
/// （`test/shared/rich_text/body_tokens_fixture_test.dart`）。**禁止在别处另写话题正则** ——
/// 展示（`RichBodyText`）、输入框高亮（`TopicMentionTextController`）、发布页 # 联想全部调这里。
///
/// 规则：
/// 1. 起点是 ASCII `#`（全角 `＃` 不算），且在行首或前一个字符**不是**话题字符（`abc#def` 不算，`(#kucing)` 算）；
/// 2. 话题字符 `[\p{L}\p{M}\p{Nd}_]`，遇其他任何字符即结束；按**码点**判（BMP 外的字母是 UTF-16 代理对）；
/// 3. 取最长串；码点数 2–30、不全是十进制数字才算（超长整段不算、不截断）；标准名小写后同样 ≤ 30 码点；
/// 4. 同标准名只占 1 个名额，前 5 个**不同**标准名有效，第 6 个起（含其重复出现）是普通文字。
///
/// <h3>标准名与后端的差异（影响范围）</h3>
/// 后端标准名 = `NFC` + `toLowerCase(Locale.ROOT)`。Dart 的 `toLowerCase` 不做上下文映射，[normalizeTopic] 补了两条
/// 与 Java `Locale.ROOT` 对齐：`İ`（U+0130）→ `i̇`（i + U+0307）、希腊词尾 `Σ` → `ς`（Final_Sigma）；两者有共用样例
/// （`dotted_I_short` / `final_sigma` / `lowercase_grows_over_30`）前后端都过。
/// **剩下的唯一差异是 NFC**（不加依赖做归一化）：正文是分解形式（NFD，如 `e` + U+0301）时 App 的 norm 与服务端不同，
/// 码点长度也按分解形式计（极长的 NFD 话题 App 可能判超长）。
/// - 展示侧（`RichBodyText`）：先精确比服务端 `topicNames`，不中再用 [matchServerTopicName] 的「去组合附标对齐」兜底，
///   命中后点击回调给**服务端的 NFC 名**；
/// - 输入框高亮 / 限 5 计数：按 App 自己的 norm 算，NFD 正文的名额判定可能与服务端差一格（极端角落，L2 不覆盖）。
///
/// 纯 Dart：不依赖 Flutter / Riverpod，可被 L0 逐条钉住。
library;

import '../../features/mention/domain/mention_token.dart';

/// 单帖最多有效话题数（同后端 `TopicNameRules.MAX_TOPICS_PER_POST`）。
const int kMaxTopicsPerPost = 5;

/// 话题名码点数下限 / 上限（不含 `#`）。
const int kTopicMinLength = 2;
const int kTopicMaxLength = 30;

final RegExp _topicChar = RegExp(r'[\p{L}\p{M}\p{Nd}_]', unicode: true);
final RegExp _decimalDigit = RegExp(r'\p{Nd}', unicode: true);

bool _isTopicCodePoint(int cp) => _topicChar.hasMatch(String.fromCharCode(cp));

/// 正文里一处话题出现（含 `#`）。
class TopicOccurrence {
  const TopicOccurrence({
    required this.start,
    required this.end,
    required this.display,
    required this.norm,
    required this.effective,
  });

  /// `#` 的 UTF-16 下标。
  final int start;

  /// 话题体结束的 UTF-16 下标（不含）。`[start, end)` 含 `#`。
  final int end;

  /// 作者原写法（不含 `#`）。
  final String display;

  /// 标准名（小写）。
  final String norm;

  /// 是否在前 5 个不同话题之内（同名重复处与首次出现相同）。
  final bool effective;

  @override
  String toString() => 'TopicOccurrence($start, $end, $display, $norm, $effective)';
}

/// 光标前正在输入的 `#关键词`（与 `MentionQuery` 同形）。
class TopicQuery {
  const TopicQuery({required this.start, required this.end, required this.keyword});

  /// `#` 的下标。
  final int start;

  /// 查询词结束的下标（= 光标位置）。
  final int end;

  /// `#` 之后已经打出来的字（可为空 —— 刚打完 `#` 的那一刻）。
  final String keyword;
}

/// 一段待高亮的区间：@（[userId] 非 null）或话题（[topicNorm] 非 null）。
class MarkRange {
  const MarkRange({required this.start, required this.end, this.userId, this.topicNorm});

  final int start;
  final int end;
  final int? userId;
  final String? topicNorm;

  bool get isMention => userId != null;

  @override
  bool operator ==(Object other) =>
      other is MarkRange &&
      other.start == start &&
      other.end == end &&
      other.userId == userId &&
      other.topicNorm == topicNorm;

  @override
  int get hashCode => Object.hash(start, end, userId, topicNorm);

  @override
  String toString() => 'MarkRange($start, $end, user=$userId, topic=$topicNorm)';
}

/// 前一个**码点**（处理代理对）；[index] 为 0 → null。
int? _codePointBefore(String text, int index) {
  if (index <= 0) return null;
  final int low = text.codeUnitAt(index - 1);
  if (index >= 2 && low >= 0xDC00 && low <= 0xDFFF) {
    final int high = text.codeUnitAt(index - 2);
    if (high >= 0xD800 && high <= 0xDBFF) {
      return 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
    }
  }
  return low;
}

/// [index] 处的码点与它占的 UTF-16 单元数。
(int, int) _codePointAt(String text, int index) {
  final int high = text.codeUnitAt(index);
  if (high >= 0xD800 && high <= 0xDBFF && index + 1 < text.length) {
    final int low = text.codeUnitAt(index + 1);
    if (low >= 0xDC00 && low <= 0xDFFF) {
      return (0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00), 2);
    }
  }
  return (high, 1);
}

/// `#` 在行首（文本开头）或前一个码点不是话题字符。
bool _atWordStart(String text, int hashIndex) {
  final int? prev = _codePointBefore(text, hashIndex);
  return prev == null || !_isTopicCodePoint(prev);
}

/// 从 [from] 起的最长话题字符串的结束下标。
int _scanTopicBody(String text, int from) {
  int j = from;
  while (j < text.length) {
    final (int cp, int width) = _codePointAt(text, j);
    if (!_isTopicCodePoint(cp)) break;
    j += width;
  }
  return j;
}

final RegExp _mark = RegExp(r'\p{M}', unicode: true);

bool _isMark(int cp) => _mark.hasMatch(String.fromCharCode(cp));

/// 有大小写之分的字符（Unicode Cased 的近似：大小写映射至少一边不是自身）。
bool _isCased(int cp) {
  final String ch = String.fromCharCode(cp);
  return ch.toLowerCase() != ch || ch.toUpperCase() != ch;
}

/// Final_Sigma（Unicode SpecialCasing，Java `toLowerCase(Locale.ROOT)` 同款）：`Σ` 之前（跳过组合附标）有 cased 字符、
/// 之后（跳过组合附标）没有 cased 字符 → `ς`。例：`ΟΔΟΣ` → `οδος`、`ΑΣ_` → `ας_`、`猫Σ` → `猫σ`（猫 不是 cased）。
bool _isFinalSigma(List<int> runes, int i) {
  int b = i - 1;
  while (b >= 0 && _isMark(runes[b])) {
    b--;
  }
  if (b < 0 || !_isCased(runes[b])) return false;
  int a = i + 1;
  while (a < runes.length && _isMark(runes[a])) {
    a++;
  }
  return a >= runes.length || !_isCased(runes[a]);
}

/// 标准名：小写，补两条与 Java `Locale.ROOT` 对齐的特殊映射（`İ` → `i̇`、词尾 `Σ` → `ς`）；不做 NFC（见库注释）。幂等。
String normalizeTopic(String raw) {
  final List<int> runes = raw.runes.toList(growable: false);
  final StringBuffer out = StringBuffer();
  for (int i = 0; i < runes.length; i++) {
    final int cp = runes[i];
    if (cp == 0x0130) {
      out.write('i\u0307'); // Dart 会把 İ 小写成不带点的 i
    } else if (cp == 0x03A3) {
      out.write(_isFinalSigma(runes, i) ? '\u03C2' : '\u03C3');
    } else {
      out.write(String.fromCharCode(cp).toLowerCase());
    }
  }
  return out.toString();
}

/// 按「基字符 + 其后的组合附标」切簇（码点）。
List<List<int>> _clusters(String s) {
  final List<int> r = s.runes.toList(growable: false);
  final List<List<int>> out = <List<int>>[];
  int i = 0;
  while (i < r.length) {
    int k = i + 1;
    while (k < r.length && _isMark(r[k])) {
      k++;
    }
    out.add(r.sublist(i, k));
    i = k;
  }
  return out;
}

bool _sameRunes(List<int> a, List<int> b) {
  if (a.length != b.length) return false;
  for (int i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

/// NFD 容错：App 的 [appNorm]（可能是分解形式）与服务端 NFC 名 [serverName] 「去组合附标对齐」后是否一致。
/// 逐簇比：簇完全相同；或 App 簇带组合附标、服务端是**单个非 ASCII 码点**（预组合字符，如 `e`+U+0301 ↔ `é`）。
///
/// ⚠️ 不是裸「删掉 \p{M} 再比」：服务端 NFC 的 `é`（U+00E9）本身不是附标，删不掉，裸删永远对不上。
/// 无分解表就无法验证「哪个」预组合字符，所以只作兜底、且 [matchServerTopicName] 要求唯一命中。
bool _foldEquals(String appNorm, String serverName) {
  final List<List<int>> a = _clusters(appNorm);
  final List<List<int>> s = _clusters(serverName);
  if (a.length != s.length) return false;
  for (int i = 0; i < a.length; i++) {
    if (_sameRunes(a[i], s[i])) continue;
    final bool precomposed = a[i].length > 1 && s[i].length == 1 && s[i][0] > 0x7F;
    if (!precomposed) return false;
  }
  return true;
}

/// 把 App 算出的 [norm] 对到服务端下发的话题名（`topicNames`）上；对不上 → null。
/// 先精确比；不中再用 NFD 容错兜底（[_foldEquals]），**唯一**命中才算（多个都像 → 不高亮，宁缺毋错）。
/// 返回服务端的名字（点击跳转 / 埋点都用它）。
String? matchServerTopicName(String norm, Set<String> serverNames) {
  if (serverNames.contains(norm)) return norm;
  String? hit;
  for (final String name in serverNames) {
    if (_foldEquals(norm, name)) {
      if (hit != null) return null;
      hit = name;
    }
  }
  return hit;
}

/// 话题名（不含 `#`）是否合法：全部为话题字符、2–30 码点、不全为数字、标准名同样 ≤ 30 码点。
bool isValidTopicName(String raw) {
  if (raw.isEmpty) return false;
  final List<int> runes = raw.runes.toList(growable: false);
  if (runes.length < kTopicMinLength || runes.length > kTopicMaxLength) return false;
  bool allDigits = true;
  for (final int cp in runes) {
    if (!_isTopicCodePoint(cp)) return false;
    if (!_decimalDigit.hasMatch(String.fromCharCode(cp))) allDigits = false;
  }
  if (allDigits) return false;
  // 小写可能变长（U+0130 → 2 码点）：标准名同样不得超过 30（同后端）。
  return normalizeTopic(raw).runes.length <= kTopicMaxLength;
}

/// 正文中**所有**合法话题出现处（按出现顺序）；同一话题多次出现每处都返回，[TopicOccurrence.effective] 相同。
List<TopicOccurrence> topicsIn(String text) {
  if (text.isEmpty) return const <TopicOccurrence>[];
  final List<TopicOccurrence> out = <TopicOccurrence>[];
  final Set<String> effectiveNorms = <String>{};
  final Set<String> overflowNorms = <String>{};
  int i = 0;
  while (i < text.length) {
    if (text.codeUnitAt(i) != 0x23 /* # */ || !_atWordStart(text, i)) {
      i++;
      continue;
    }
    final int bodyStart = i + 1;
    final int j = _scanTopicBody(text, bodyStart);
    final String raw = text.substring(bodyStart, j);
    if (isValidTopicName(raw)) {
      final String norm = normalizeTopic(raw);
      final bool effective;
      if (effectiveNorms.contains(norm)) {
        effective = true;
      } else if (overflowNorms.contains(norm)) {
        effective = false;
      } else if (effectiveNorms.length < kMaxTopicsPerPost) {
        effectiveNorms.add(norm);
        effective = true;
      } else {
        overflowNorms.add(norm);
        effective = false;
      }
      out.add(TopicOccurrence(start: i, end: j, display: raw, norm: norm, effective: effective));
    }
    // 话题体（合法与否）整段跳过；空体时 j == bodyStart，下一个 # 由循环重新判定（##kucing 取第二个）。
    i = j > i + 1 ? j : i + 1;
  }
  return List<TopicOccurrence>.unmodifiable(out);
}

/// 前 5 个不同标准名（取首次出现那一处，`display` 为首次写法），按出现顺序。
List<TopicOccurrence> effectiveDistinct(String text) {
  final Set<String> seen = <String>{};
  return List<TopicOccurrence>.unmodifiable(
      topicsIn(text).where((t) => t.effective && seen.add(t.norm)));
}

/// 光标前是否正在输入一个 `#关键词`；不是 → null（# 联想面板不该出现）。
///
/// 认定：`#` 在词首（同 [topicsIn] 的起点规则）、`#` 与光标之间全是话题字符、关键词 ≤ 30 码点（可为空）。
TopicQuery? topicQueryAt(String text, int cursor) {
  if (cursor <= 0 || cursor > text.length) return null;
  int k = cursor;
  while (k > 0) {
    final int? cp = _codePointBefore(text, k);
    if (cp == null || !_isTopicCodePoint(cp)) break;
    k -= cp > 0xFFFF ? 2 : 1;
  }
  if (k <= 0 || text.codeUnitAt(k - 1) != 0x23 /* # */) return null;
  final int hash = k - 1;
  if (!_atWordStart(text, hash)) return null;
  final String keyword = text.substring(k, cursor);
  if (keyword.runes.length > kTopicMaxLength) return null;
  return TopicQuery(start: hash, end: cursor, keyword: keyword);
}

/// 正文里每一处 `@昵称` 的区间（按位置升序、互不重叠）。
///
/// 从 `MentionText._locate` 原样搬来：
/// - 边界判定只用 `MentionToken.indexOf`（写入侧 / 渲染侧共用，2026-09-15 分叉事故）；
/// - 昵称长的先占（同时有「An」「Ana」时不切错）；
/// - [dropAmbiguous] = true：同一昵称对应多个人 → 一律不高亮（展示侧必须开：高亮了就是把读者送进确定错误的主页）；
///   输入侧关（同名都高亮，与 `MentionDraft.userIdsIn` 一致）。
List<MarkRange> mentionRanges(
  String text,
  Iterable<({int userId, String nickname})> refs, {
  required bool dropAmbiguous,
}) {
  final Map<String, List<({int userId, String nickname})>> byNickname =
      <String, List<({int userId, String nickname})>>{};
  for (final r in refs) {
    if (r.nickname.isEmpty) continue;
    byNickname.putIfAbsent(r.nickname, () => <({int userId, String nickname})>[]).add(r);
  }
  final List<({int userId, String nickname})> candidates = byNickname.values
      .where((group) => !dropAmbiguous || group.map((m) => m.userId).toSet().length == 1)
      .map((group) => group.first)
      .toList()
    ..sort((a, b) => b.nickname.length.compareTo(a.nickname.length));
  if (candidates.isEmpty || text.isEmpty) return const <MarkRange>[];

  final List<MarkRange> hits = <MarkRange>[];
  final List<bool> taken = List<bool>.filled(text.length, false);
  for (final m in candidates) {
    int from = 0;
    while (true) {
      final int at = MentionToken.indexOf(text, m.nickname, from);
      if (at < 0) break;
      final int end = at + m.nickname.length + 1; // +1 = '@'
      bool overlap = false;
      for (int i = at; i < end; i++) {
        if (taken[i]) {
          overlap = true;
          break;
        }
      }
      if (!overlap) {
        for (int i = at; i < end; i++) {
          taken[i] = true;
        }
        hits.add(MarkRange(start: at, end: end, userId: m.userId));
      }
      from = at + 1;
    }
  }
  hits.sort((a, b) => a.start.compareTo(b.start));
  return List<MarkRange>.unmodifiable(hits);
}

/// 合并 @ 区间与话题出现处：**@ 优先**（昵称里含 `#` 的极端情况，落在 @ 区间内的话题丢弃）。按位置升序、互不重叠。
List<MarkRange> mergeMarks(List<MarkRange> mentions, Iterable<TopicOccurrence> topics) {
  final List<MarkRange> out = List<MarkRange>.of(mentions);
  for (final t in topics) {
    final bool overlaps = mentions.any((m) => t.start < m.end && m.start < t.end);
    if (!overlaps) out.add(MarkRange(start: t.start, end: t.end, topicNorm: t.norm));
  }
  out.sort((a, b) => a.start.compareTo(b.start));
  return List<MarkRange>.unmodifiable(out);
}
