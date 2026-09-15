import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../domain/mention_token.dart';
import '../domain/mention_view.dart';

/// 把「正文里的 @昵称」渲染成可点高亮片段（V1.3.0 batch-b1 Story 3.3 · AC1/AC2）。
///
/// <h3>🔴 只高亮后端说可点的那些（AC3/AC4）</h3>
/// 本组件**不做任何拉黑 / 注销判定** —— 那是服务端的事（`MentionView.tappable`）。
/// 不可点的 @ 原样当普通文字渲染：不变色、点不动。
///
/// <h3>🔴 `TapGestureRecognizer` 只在 [State] 生命周期里建与放，绝不在 `build` 里</h3>
/// `OneSequenceGestureRecognizer.dispose()` 会 `resolve(rejected)`。所以在 `build` 里
/// dispose 上一批 recognizer 是个**间歇性 bug**：手指按下到抬起之间只要发生一次重建
/// （provider 刷新、键盘 inset 变化、祖先 setState），正在竞技的那个 recognizer 被判负，
/// **外层手势反而赢** —— Feed 卡片变成进详情页、评论行变成弹回复框，`onTapUser`
/// 根本不触发（code-review 2026-09-15）。
/// 所以切片与 recognizer 都在 `initState` / `didUpdateWidget` 里算好缓存，
/// `build` 只负责拼 span。
///
/// <h3>⚠️ 定位靠「当前昵称」，所以改名过的 @ 会退化成普通文字</h3>
/// 正文里存的是写入那一刻的「@旧昵称」，而后端下发的是**当前**昵称（AC1）。
/// 两者不一致时这里找不到那一段，于是不高亮 —— 内容照常显示，只是点不动。
/// 这与 Story 3.5「存量 @ 不回溯解析」渲染出来的样子是同一种（已知取舍，
/// 见 story Completion Notes 的 🔸 一条）。
class MentionText extends StatefulWidget {
  const MentionText({
    super.key,
    required this.text,
    required this.mentions,
    required this.onTapUser,
    this.style,
    this.maxLines,
    this.overflow,
  });

  final String text;

  /// 后端下发的 @ 投影（空表 = 这段文字里没有可点的 @，退化成纯 [Text]）。
  final List<MentionView> mentions;

  /// 点了某个 @ → 去那个人的公开主页（AC2）。
  final void Function(int userId) onTapUser;

  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;

  @override
  State<MentionText> createState() => _MentionTextState();
}

class _MentionTextState extends State<MentionText> {
  /// 已切好的可点片段（按位置升序、互不重叠），每段自带一个 recognizer。
  List<_Hit> _hits = const <_Hit>[];

  @override
  void initState() {
    super.initState();
    _rebuildHits();
  }

  @override
  void didUpdateWidget(MentionText oldWidget) {
    super.didUpdateWidget(oldWidget);
    // ⚠️ 只在**内容真的变了**时重算 —— recognizer 的生死绑在这里，
    //    绑到 build 上就会被无关重建打断（见类注释）。
    //    onTapUser 变了不必重算：recognizer 里读的是 `widget.onTapUser`。
    if (widget.text != oldWidget.text || !listEquals(widget.mentions, oldWidget.mentions)) {
      _rebuildHits();
    }
  }

  @override
  void dispose() {
    _disposeHits();
    super.dispose();
  }

  void _disposeHits() {
    for (final hit in _hits) {
      hit.recognizer.dispose();
    }
    _hits = const <_Hit>[];
  }

  void _rebuildHits() {
    _disposeHits();
    _hits = _locate()
        .map((r) => _Hit(
              start: r.start,
              end: r.end,
              userId: r.userId,
              // 闭包里读 widget.onTapUser 而不是捕获当前值：回调换了不必重建 recognizer。
              recognizer: TapGestureRecognizer()..onTap = () => widget.onTapUser(r.userId),
            ))
        .toList(growable: false);
  }

  @override
  Widget build(BuildContext context) {
    // 没有一处可点 → 就是一个普通 Text（不白建富文本，也不挂任何 recognizer）。
    if (_hits.isEmpty) {
      return Text(widget.text,
          style: widget.style, maxLines: widget.maxLines, overflow: widget.overflow);
    }
    final base = widget.style ?? DefaultTextStyle.of(context).style;
    return Text.rich(
      TextSpan(children: _spans(base)),
      style: widget.style,
      maxLines: widget.maxLines,
      overflow: widget.overflow,
    );
  }

  /// 「普通文字 / 可点 @」交替的片段。
  List<InlineSpan> _spans(TextStyle base) {
    final List<InlineSpan> spans = <InlineSpan>[];
    int cursor = 0;
    for (final hit in _hits) {
      if (hit.start > cursor) {
        spans.add(TextSpan(text: widget.text.substring(cursor, hit.start)));
      }
      spans.add(TextSpan(
        text: widget.text.substring(hit.start, hit.end),
        // 高亮就是品牌色 + 中等字重；刻意**不加下划线** —— 正文里一段下划线更像链接广告。
        style: base.copyWith(color: AppColors.mint600, fontWeight: FontWeight.w600),
        recognizer: hit.recognizer,
      ));
      cursor = hit.end;
    }
    if (cursor < widget.text.length) {
      spans.add(TextSpan(text: widget.text.substring(cursor)));
    }
    return spans;
  }

  /// 在正文里找出每一处 `@当前昵称`，按位置排序、互不重叠。
  List<_Range> _locate() {
    // 🔴 **同名的候选一律不高亮**（code-review 2026-09-15）：候选里两个人都叫「Budi」时，
    //    正文里那一处「@Budi」根本无从判断是谁 —— 高亮了就是把读者送进**确定错误**的主页。
    //    写入侧那边同名的代价只是多发一条通知（已作为已知不精确接受），这里代价大得多。
    final Map<String, List<MentionView>> byNickname = <String, List<MentionView>>{};
    for (final m in widget.mentions.where((m) => m.highlightable)) {
      byNickname.putIfAbsent(m.nickname!, () => <MentionView>[]).add(m);
    }
    final List<MentionView> candidates = byNickname.values
        .where((group) => group.map((m) => m.userId).toSet().length == 1)
        .map((group) => group.first)
        .toList()
      // ⚠️ 昵称长的先匹配：同时有「An」与「Ana」时，短的先来会把「@Ana」切成
      //    「@An」+「a」——「@An」那段的边界判定虽然已经挡住了它，但排序仍然保留：
      //    它让"先占长的"这件事不依赖边界判定这一道防线。
      ..sort((a, b) => b.nickname!.length.compareTo(a.nickname!.length));
    if (candidates.isEmpty) return const <_Range>[];

    final List<_Range> hits = <_Range>[];
    final List<bool> taken = List<bool>.filled(widget.text.length, false);
    for (final m in candidates) {
      int from = 0;
      while (true) {
        // 🔴 边界判定与写入侧共用同一份（MentionToken）：裸 indexOf 会让
        //    「@An」匹配到「@Ana」，点进去是错的人。
        final int at = MentionToken.indexOf(widget.text, m.nickname!, from);
        if (at < 0) break;
        final int end = at + m.nickname!.length + 1; // +1 = '@'
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
          hits.add(_Range(start: at, end: end, userId: m.userId));
        }
        from = at + 1;
      }
    }
    hits.sort((a, b) => a.start.compareTo(b.start));
    return hits;
  }
}

/// 一处待高亮的区间（还没挂 recognizer）。
class _Range {
  const _Range({required this.start, required this.end, required this.userId});

  final int start;
  final int end;
  final int userId;
}

/// 一处已挂 recognizer 的高亮片段。
class _Hit {
  const _Hit({
    required this.start,
    required this.end,
    required this.userId,
    required this.recognizer,
  });

  final int start;
  final int end;
  final int userId;
  final TapGestureRecognizer recognizer;
}
