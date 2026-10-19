import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/growth/domain/growth_badge.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/social/data/following_strip_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';

/// V1.4.0 ① Story 3.2 · AC5.2：七个模型解析等级三字段，整对象拷贝方法原样带过去（L0）。
const _badge = GrowthBadge(level: 12, tier: GrowthTier.guardian, frame: GrowthFrame.upgraded);
const _author = {'authorLevel': 12, 'authorTier': 'GUARDIAN', 'authorFrame': 'UPGRADED'};
const _flat = {'level': 12, 'tier': 'GUARDIAN', 'frame': 'UPGRADED'};
const _base = {'id': 1, 'authorId': 7, 'createdAt': '2026-10-18T00:00:00Z'};

void main() {
  test('FeedItem：解析 + copyWith 带过去；无字段 → null', () {
    final f = FeedItem.fromJson({..._base, ..._author});
    expect(f.authorBadge, _badge);
    expect(f.copyWith(likeCount: 3).authorBadge, _badge);
    expect(FeedItem.fromJson({..._base}).authorBadge, isNull);
  });

  test('ContentDetail：解析 + withFollowEpoch 带过去；无字段 → null', () {
    final d = ContentDetail.fromJson({..._base, ..._author});
    expect(d.authorBadge, _badge);
    expect(d.withFollowEpoch(2).authorBadge, _badge);
    expect(ContentDetail.fromJson({..._base}).authorBadge, isNull);
  });

  test('Comment：解析 + copyWith 带过去；无字段 → null', () {
    final c = Comment.fromJson({..._base, ..._author});
    expect(c.authorBadge, _badge);
    expect(c.copyWith(likeCount: 1).authorBadge, _badge);
    expect(c.toggleLikedLocally().authorBadge, _badge);
    expect(Comment.fromJson({..._base}).authorBadge, isNull);
  });

  test('PlaceComment：解析；无字段 → null', () {
    expect(PlaceComment.fromJson({..._base, ..._author}).authorBadge, _badge);
    expect(PlaceComment.fromJson({..._base}).authorBadge, isNull);
  });

  test('PublicProfile（无前缀）：解析 + withFollowEpoch 带过去；无字段 → null', () {
    final p = PublicProfile.fromJson({'nickname': 'Rina', ..._flat});
    expect(p.badge, _badge);
    expect(p.withFollowEpoch(3).badge, _badge);
    expect(PublicProfile.fromJson({'nickname': 'Rina'}).badge, isNull);
  });

  test('FollowListItem（无前缀）：解析 + withFollowEpoch 带过去；无字段 → null', () {
    final i = FollowListItem.fromJson({'userId': 7, ..._flat});
    expect(i.badge, _badge);
    expect(i.withFollowEpoch(1).badge, _badge);
    expect(FollowListItem.fromJson({'userId': 7}).badge, isNull);
  });

  test('FollowingStripUser（无前缀）：解析；无字段 → null', () {
    expect(FollowingStripUser.fromJson({'userId': 7, 'userToken': 't', ..._flat}).badge, _badge);
    expect(FollowingStripUser.fromJson({'userId': 7, 'userToken': 't'}).badge, isNull);
  });
}
