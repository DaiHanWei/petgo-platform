import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/mention/data/mention_candidate_repository.dart';
import 'package:tailtopia/features/mention/domain/mention_relation.dart';

/// L0：@ 候选关系枚举（V1.4.2 social-topic · Story 5.2 · AC4；接口字段来自 Story 5.1）。
void main() {
  test('wire 取值 = 接口 / 埋点口径（小写）', () {
    expect(MentionRelation.values.map((e) => e.wire), ['following', 'follower', 'interacted']);
  });

  test('parse：三值原样；未知 / null → interacted', () {
    expect(MentionRelation.parse('following'), MentionRelation.following);
    expect(MentionRelation.parse('follower'), MentionRelation.follower);
    expect(MentionRelation.parse('interacted'), MentionRelation.interacted);
    expect(MentionRelation.parse(null), MentionRelation.interacted);
    expect(MentionRelation.parse('FOLLOWING'), MentionRelation.interacted);
    expect(MentionRelation.parse('friend'), MentionRelation.interacted);
  });

  test('MentionCandidate.fromJson 解析 relation；缺失兜底 interacted', () {
    final a = MentionCandidate.fromJson({'userId': 1, 'nickname': 'A', 'relation': 'follower'});
    expect(a.relation, MentionRelation.follower);
    final b = MentionCandidate.fromJson({'userId': 2, 'nickname': 'B'});
    expect(b.relation, MentionRelation.interacted);
  });
}
