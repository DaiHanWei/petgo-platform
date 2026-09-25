// characters 扩展（字素计数）由 flutter 再导出 —— 与 TextField.maxLength 同一口径。
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/mention/domain/mention_draft.dart';

/// L0：@ 的触发规则、插入与绑定、5 人上限
/// （V1.3.0 batch-b1 Story 3.2 · AC1/AC4/AC5 · FR-119 · AD-10 Rule 4）。
///
/// 这一层是纯逻辑，所以能在 L0 钉住 story 里最容易写坏的三件事：
/// 什么时候才该弹浮层、插进去之后 id 有没有真的被记住、上限是不是硬的。
void main() {
  group('AC1 触发规则（什么时候该弹浮层）', () {
    test('刚打完 @ 就弹，关键词为空', () {
      final q = MentionDraft.queryAt('@', 1);
      expect(q, isNotNull);
      expect(q!.keyword, '');
      expect(q.start, 0);
    });

    test('@ 后面接着打字 → 关键词跟着走（AC2 的过滤条件来源）', () {
      expect(MentionDraft.queryAt('hai @au', 7)?.keyword, 'au');
    });

    test('@ 前面是空白也算词首', () {
      expect(MentionDraft.queryAt('hai @', 5), isNotNull);
    });

    test('🔴 邮箱里的 @ 不弹浮层', () {
      // ⚠️ 少了「@ 必须在词首」这条，`user@mail.com` 打到一半就会弹出人名列表。
      expect(MentionDraft.queryAt('user@', 5), isNull);
      expect(MentionDraft.queryAt('user@mail', 9), isNull);
    });

    test('@ 与光标之间出现空格 → 浮层收起', () {
      // 打完 @ 又打空格，说明用户不是在 @ 人。
      expect(MentionDraft.queryAt('@ ', 2), isNull);
      expect(MentionDraft.queryAt('@au bc', 6), isNull);
    });

    test('关键词过长 → 浮层收起（不是在 @ 人，只是正文里有个 @）', () {
      expect(MentionDraft.queryAt('@${'a' * 21}', 22), isNull);
    });

    test('光标在 @ 之前 → 不弹', () {
      expect(MentionDraft.queryAt('ab@cd', 2), isNull);
    });

    test('空文本 / 光标 0 → 不弹（不是异常）', () {
      expect(MentionDraft.queryAt('', 0), isNull);
      expect(MentionDraft.queryAt('abc', 0), isNull);
    });

    test('光标越界 → 不弹（输入法竞态下真的会传进来）', () {
      expect(MentionDraft.queryAt('abc', 99), isNull);
      expect(MentionDraft.queryAt('abc', -1), isNull);
    });
  });

  group('AC4 插入 @昵称、数据层绑 userId', () {
    test('把 @关键词 整段换成 @昵称 + 空格，并记下 userId', () {
      final draft = MentionDraft();
      const text = 'hai @au';
      final q = MentionDraft.queryAt(text, 7)!;
      final ins = draft.insert(text, q, 42, 'Aurel')!;

      expect(ins.text, 'hai @Aurel ');
      expect(ins.cursor, ins.text.length); // 光标落在空格之后，可以接着打字
      // 🔴 文本里是昵称，数据层是 id。
      expect(draft.refs.single.userId, 42);
      expect(draft.userIdsIn(ins.text), [42]);
    });

    test('插在句子中间时只替换 @ 那一段，后面的字原样留着', () {
      final draft = MentionDraft();
      const text = 'hai @au ya';
      final q = MentionDraft.queryAt(text, 7)!;
      expect(draft.insert(text, q, 7, 'Aurel')!.text, 'hai @Aurel  ya');
    });

    test('🔴 提交时只认文本里还留着 @昵称 的那些 id', () {
      // 用户插完又把那串字删掉（或整段重写）是常事。照单全发的话，对方会收到
      // 一条「有人 @ 了你」、点进去正文里根本没有他。
      final draft = MentionDraft();
      final ins = draft.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel')!;
      expect(draft.userIdsIn(ins.text), [42]);
      expect(draft.userIdsIn('我把那串字删了'), isEmpty);
      expect(draft.refs, hasLength(1)); // 绑定还在（用户可能再打回来），只是这次不发
    });

    test('同一个人重复选中：不占新名额、不重复提交', () {
      final draft = MentionDraft();
      final a = draft.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel')!;
      final b = draft.insert('${a.text}@', MentionDraft.queryAt('${a.text}@', a.text.length + 1)!,
          42, 'Aurel')!;
      expect(draft.refs, hasLength(1));
      expect(draft.userIdsIn(b.text), [42]); // 文本里两处 @Aurel，id 只发一次
    });

    test('🔴 昵称是别的昵称的前缀时不误判（code-review 2026-09-15）', () {
      // @ 了「An」又删掉，后文里写了「@Ana」—— 裸 contains 会认为 An 还在，
      // 于是给 An 发一条他并不在其中的通知。
      final draft = MentionDraft();
      draft.insert('@', MentionDraft.queryAt('@', 1)!, 1, 'An');
      expect(draft.userIdsIn('halo @Ana'), isEmpty);
      // 完整出现时当然要算上（后面跟标点 / 空白 / 字符串结尾都算完整）。
      expect(draft.userIdsIn('halo @An'), [1]);
      expect(draft.userIdsIn('halo @An ya'), [1]);
      expect(draft.userIdsIn('halo @An, apa kabar'), [1]);
    });

    test('前缀昵称与完整昵称同时在文本里 → 两个都算', () {
      final draft = MentionDraft();
      draft.insert('@', MentionDraft.queryAt('@', 1)!, 1, 'An');
      draft.insert('@', MentionDraft.queryAt('@', 1)!, 2, 'Ana');
      expect(draft.userIdsIn('halo @Ana dan @An'), [1, 2]);
    });

    test('非 ASCII 昵称的边界判定同样有效', () {
      // 只认 [A-Za-z0-9] 的话，中文 / 带重音的印尼语昵称全部失效。
      final draft = MentionDraft();
      draft.insert('@', MentionDraft.queryAt('@', 1)!, 1, '阿花');
      expect(draft.userIdsIn('今天 @阿花花 很开心'), isEmpty);
      expect(draft.userIdsIn('今天 @阿花 很开心'), [1]);
    });

    test('同一个人昵称变了 → 以最新那次为准', () {
      // 不更新的话，userIdsIn 会拿旧昵称去文本里找，找不到就把这个人悄悄丢了。
      final draft = MentionDraft();
      draft.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel');
      final ins = draft.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel2')!;
      expect(draft.refs.single.nickname, 'Aurel2');
      expect(draft.userIdsIn(ins.text), [42]);
    });

    test('clear() 之后既没有绑定也不再提交任何 id', () {
      final draft = MentionDraft();
      final ins = draft.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel')!;
      draft.clear();
      expect(draft.refs, isEmpty);
      expect(draft.userIdsIn(ins.text), isEmpty);
    });
  });

  group('AC5 上限 5 人', () {
    MentionInsertion? insertNth(MentionDraft draft, int i) {
      const text = '@';
      return draft.insert(text, MentionDraft.queryAt(text, 1)!, 100 + i, 'U$i');
    }

    test('第 6 个人插不进去（返回 null 供调用方提示）', () {
      final draft = MentionDraft();
      for (int i = 0; i < MentionDraft.maxMentions; i++) {
        expect(insertNth(draft, i), isNotNull, reason: '第 ${i + 1} 个人应能插入');
      }
      expect(draft.isFull, isTrue);
      expect(insertNth(draft, 9), isNull); // 达上限 → 不插入 + 调用方给 toast
      expect(draft.refs, hasLength(MentionDraft.maxMentions));
    });

    test('满了之后重复选已 @ 过的人仍然可以（不占新名额）', () {
      final draft = MentionDraft();
      for (int i = 0; i < MentionDraft.maxMentions; i++) {
        insertNth(draft, i);
      }
      expect(insertNth(draft, 0), isNotNull);
      expect(draft.refs, hasLength(MentionDraft.maxMentions));
    });

    test('上限就是 5（与服务端 MentionSanitizer.MAX_MENTIONS 同一个数）', () {
      expect(MentionDraft.maxMentions, 5);
    });
  });

  group('🔴 插入不得绕过输入框字数上限（code-review 2026-09-15）', () {
    test('textAfterInsert 是纯预演：给出替换后的文本，不改任何状态', () {
      final draft = MentionDraft();
      const text = 'hai @au';
      final q = MentionDraft.queryAt(text, 7)!;
      expect(MentionDraft.textAfterInsert(text, q, 'Aurel'), 'hai @Aurel ');
      expect(draft.refs, isEmpty, reason: '预演不得记下绑定');
    });

    test('调用方据此能在插入前就判超限（评论 200 / 正文 1000 各自的上限）', () {
      final text = '${'a' * 197} @'; // @ 必须在词首，所以前面留一个空格
      final q = MentionDraft.queryAt(text, text.length)!;
      // 197 + ' ' + '@Aurel ' = 205 > 200：插进去必被服务端 @Size(max=200) 拒，
      // 而用户只看到通用的「发送失败，请重试」，重试永远不会成功。
      expect(MentionDraft.textAfterInsert(text, q, 'Aurel').characters.length,
          greaterThan(200));
    });

    test('字素数而非码元数（emoji 昵称不会被算成两三个字）', () {
      const text = '@';
      final q = MentionDraft.queryAt(text, 1)!;
      // '@' + '🐶' + ' ' = 3 个字素（码元数是 4）—— 调用方用 characters 量，
      // 与 TextField.maxLength 同一口径。
      final next = MentionDraft.textAfterInsert(text, q, '🐶');
      expect(next.characters.length, 3);
      expect(next.length, 4);
    });
  });
}
