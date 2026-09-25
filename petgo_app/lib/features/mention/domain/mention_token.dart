/// 「正文里那串 `@昵称`」的边界判定 —— **写入侧与渲染侧共用同一份**
/// （V1.3.0 batch-b1 Story 3.2 / 3.3）。
///
/// <h3>🔴 为什么非要抽出来</h3>
/// 写入侧要回答「这个 @ 还在不在文本里」（`MentionDraft.userIdsIn`），
/// 渲染侧要回答「这个 @ 在文本里的哪一段」（`MentionText`）。两处判的是同一件事，
/// 而 2026-09-15 的 code-review 抓到的正是**只在写入侧加了边界守卫、渲染侧漏了**：
/// 结果 @ 过「An」的正文里写了「@Ana」，渲染侧把它切成 `@An` + `a`，
/// 点进去是**错的人**。所以判定只留一处。
class MentionToken {
  const MentionToken._();

  /// 「昵称还没结束 / 这个 @ 不在词首」的判据。
  ///
  /// 用 Unicode 字母 + 数字类而不是 `[A-Za-z0-9]` —— 印尼语昵称带重音字母、
  /// 中文昵称全是 `\p{L}`，只认 ASCII 等于对它们全部失效。
  static final RegExp _wordChar = RegExp(r'[\p{L}\p{N}_]', unicode: true);

  /// 在 [text] 里从 [from] 开始找下一处**完整的** `@昵称`，返回 `@` 的下标；没有则 -1。
  ///
  /// 「完整」要求两头都不是字母 / 数字 / 下划线：
  /// - 前面：挡掉 `mail@Aurel` 这类（`@` 不在词首，不是一次提及）；
  ///   ⚠️ 判的是"非单词字符"而不是"空白" —— 用户手写的 `(@Aurel)` 也该算。
  /// - 后面：挡掉「@An」匹配到「@Ana」这类前缀误判。
  static int indexOf(String text, String nickname, [int from = 0]) {
    if (nickname.isEmpty) return -1;
    final String token = '@$nickname';
    int cursor = from;
    while (true) {
      final int at = text.indexOf(token, cursor);
      if (at < 0) return -1;
      final int end = at + token.length;
      final bool startOk = at == 0 || !_wordChar.hasMatch(text[at - 1]);
      final bool endOk = end >= text.length || !_wordChar.hasMatch(text[end]);
      if (startOk && endOk) return at;
      cursor = at + 1; // 这一处不算，接着往后找
    }
  }

  /// 文本里是否还有至少一处完整的 `@昵称`。
  static bool contains(String text, String nickname) => indexOf(text, nickname) >= 0;
}
