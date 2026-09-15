import 'mention_token.dart';

/// 一条已经插进正文的 @（V1.3.0 batch-b1 Story 3.2 · AC4）。
///
/// 🔴 [userId] 是**被提交、被存库**的那一份；[nickname] 只是它当时在文本里长什么样，
/// 用来在提交前核对「这串字还在不在」（见 [MentionDraft.userIdsIn]）。
class MentionRef {
  const MentionRef({required this.userId, required this.nickname});

  final int userId;
  final String nickname;

  @override
  bool operator ==(Object other) =>
      other is MentionRef && other.userId == userId && other.nickname == nickname;

  @override
  int get hashCode => Object.hash(userId, nickname);
}

/// 光标前那个正在输入的「@查询词」（AC1/AC2）。
class MentionQuery {
  const MentionQuery({required this.start, required this.end, required this.keyword});

  /// `@` 本身在文本里的下标。
  final int start;

  /// 查询词结束的下标（= 光标位置）。
  final int end;

  /// `@` 后面已经打出来的那几个字（可能为空 —— 刚打完 `@` 的那一刻）。
  final String keyword;
}

/// 文本 + 光标的一次替换结果。
class MentionInsertion {
  const MentionInsertion({required this.text, required this.cursor});

  final String text;
  final int cursor;
}

/// 正文 / 评论里 @ 的**数据层**（Story 3.2 · AC4/AC5）。
///
/// <h3>🔴 它存在的全部理由：文本里是昵称，提交出去的是 userId</h3>
/// 「@昵称」是给人读的；对方改个名，这串字就对不上人了。所以每插一个 @ 就在这里
/// 记一条 (userId, 当时的昵称)，提交时把**文本里还留着的**那些换算成 id 交给服务端
/// （AD-10 Rule 4）。
///
/// <h3>⚠️ 这是纯 Dart 类，刻意不依赖 Flutter / Riverpod</h3>
/// 触发规则、上限、"删字之后这个 @ 还算不算数"全是纯逻辑，值得被 L0 逐条钉住 ——
/// 混进 widget 里就只能靠 L2 人眼看了。
class MentionDraft {
  MentionDraft();

  /// 单条内容 / 单条评论最多 @ 几个人（AC5，防骚扰）。服务端同样把关（`MentionSanitizer`）。
  static const int maxMentions = 5;

  /// `@` 后面最多认多长的查询词。超过就认为用户不是在 @ 人，只是在写
  /// 邮箱之类含 `@` 的普通文本 —— 浮层该悄悄收起来，而不是一直挂在那儿。
  static const int _maxKeywordLength = 20;

  final List<MentionRef> _refs = <MentionRef>[];

  /// 已经插进去的 @（按插入顺序）。
  List<MentionRef> get refs => List<MentionRef>.unmodifiable(_refs);

  bool get isFull => _refs.length >= maxMentions;

  void clear() => _refs.clear();

  /// 光标前是否正在打一个 @ 查询词；不是则返回 null（浮层不该出现）。
  ///
  /// 认定规则（三条缺一不可）：
  /// 1. 光标前存在 `@`；
  /// 2. 那个 `@` 处在**词首** —— 行首，或前一个字符是空白。
  ///    ⚠️ 少了这条，`user@mail.com` 打到一半就会弹人名出来；
  /// 3. `@` 与光标之间没有空白、没有第二个 `@`，且不超过 [_maxKeywordLength] 个字。
  static MentionQuery? queryAt(String text, int cursor) {
    // cursor == 0 单独挡掉：lastIndexOf 的 start 传 -1 是未定义用法。
    if (cursor <= 0 || cursor > text.length) return null;
    final int at = text.lastIndexOf('@', cursor - 1);
    if (at < 0) return null;
    if (at > 0 && !_isWhitespace(text[at - 1])) return null;
    final String keyword = text.substring(at + 1, cursor);
    if (keyword.length > _maxKeywordLength) return null;
    for (final int unit in keyword.runes) {
      final String ch = String.fromCharCode(unit);
      if (ch == '@' || _isWhitespace(ch)) return null;
    }
    return MentionQuery(start: at, end: cursor, keyword: keyword);
  }

  /// 把 [query] 那段 `@关键词` 换成 `@昵称 `，并记下 (userId, 昵称) 的绑定。
  ///
  /// 达上限时返回 null（调用方给 toast —— AC5 要求"不能再插入并给出提示"）。
  /// ⚠️ 已经 @ 过的同一个人**重复选中不占新名额**，也不重复记一条：
  /// 服务端会去重，而这里若不判，用户 @ 同一个人五次就把名额用光了。
  MentionInsertion? insert(String text, MentionQuery query, int userId, String nickname) {
    final bool already = _refs.any((r) => r.userId == userId);
    if (!already && isFull) return null;

    // 尾随一个空格：下一个字不会粘在昵称后面，也让 userIdsIn 的"这串字还在不在"
    // 判定不至于被后续输入撑破。
    final String inserted = '@$nickname ';
    final String next = text.replaceRange(query.start, query.end, inserted);
    if (!already) {
      _refs.add(MentionRef(userId: userId, nickname: nickname));
    } else {
      // 昵称可能与上次插入时不同（对方刚改名）：以最新这次为准，
      // 否则 userIdsIn 会拿旧昵称去文本里找，找不到就把这个人悄悄丢了。
      final int i = _refs.indexWhere((r) => r.userId == userId);
      _refs[i] = MentionRef(userId: userId, nickname: nickname);
    }
    return MentionInsertion(text: next, cursor: query.start + inserted.length);
  }

  /// 插入 `@昵称 ` 之后文本会长什么样 —— **不改任何状态**，纯预演。
  ///
  /// 调用方**必须先拿它的 `characters.length` 跟输入框上限比一次**再调 [insert]：
  /// 插入是直接写 `TextEditingController.value`，**绕过 `maxLength` 的输入格式化器** ——
  /// 200 字的评论再插一个长昵称就成了 218 字，提交必被服务端 `@Size(max=200)` 拒，
  /// 而用户只看到一句通用的「发送失败，请重试」，重试永远也不会成功
  /// （code-review 2026-09-15）。
  ///
  /// ⚠️ 返回的是**文本**而不是长度：字素计数要 `package:characters`，而本类刻意保持
  /// 纯 Dart（见类注释）。长度由调用方在 widget 侧量，与它自己那个 `maxLength`
  /// 用同一个口径。
  static String textAfterInsert(String text, MentionQuery query, String nickname) =>
      text.replaceRange(query.start, query.end, '@$nickname ');

  /// 提交时要发给服务端的 userId 列表（AC4）。
  ///
  /// 🔴 **只认文本里还留着 `@昵称` 的那些**。用户插完又把那串字删掉（或整段重写）是
  /// 常事，若照单全发，对方会收到一条「有人 @ 了你」、点进去正文里根本没有他。
  ///
  /// ⚠️ 这里做的是「文本里还有没有这串字」的核对，不是精确的区段追踪 ——
  /// 精确追踪要在每次输入上维护偏移量，而代价不值得。真正的权威过滤在服务端
  /// （`MentionSanitizer`：与候选集求交 / 去重 / 去自己 / 去注销 / 去拉黑）。
  ///
  /// ⚠️ **残留的已知不精确**：候选集里两个人昵称一模一样时（真有可能），
  /// 文本里一处 `@同名` 会把两个 id 都发出去，于是两人各收到一条通知。
  /// 要分清得在文本里埋不可见标记，代价远大于这点误差。
  List<int> userIdsIn(String text) {
    final List<int> out = <int>[];
    for (final MentionRef ref in _refs) {
      if (out.contains(ref.userId)) continue;
      // 🔴 边界判定与渲染侧共用同一份（MentionToken）—— 两处分叉过一次：
      //    @ 了「An」又删掉、后文写了「@Ana」，裸 contains 会认为 An 还在。
      if (MentionToken.contains(text, ref.nickname)) out.add(ref.userId);
    }
    return out;
  }

  static bool _isWhitespace(String ch) => ch.trim().isEmpty;
}
