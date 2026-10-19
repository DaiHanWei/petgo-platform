import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/collect/domain/collected_item.dart';

/// V1.4.0 ④ Story 2.4 · AC6.1：列表行解析（宽进）。
void main() {
  test('可见帖子字段齐全', () {
    final p = CollectedPost.fromJson({
      'postId': 5,
      'collectedAt': '2026-10-18T05:00:00Z',
      'available': true,
      'post': {
        'type': 'GROWTH_MOMENT',
        'excerpt': 'halo',
        'coverUrl': 'https://cdn/1.jpg',
        'author': {'nickname': 'Rina', 'avatarUrl': 'https://cdn/a.jpg'},
      },
    });
    expect(p.postId, 5);
    expect(p.collectedAt, DateTime.utc(2026, 10, 18, 5));
    expect(p.showsAsAvailable, isTrue);
    expect(p.post!.type, 'GROWTH_MOMENT');
    expect(p.post!.excerpt, 'halo');
    expect(p.post!.coverUrl, 'https://cdn/1.jpg');
    expect(p.post!.authorNickname, 'Rina');
    expect(p.post!.authorAvatarUrl, 'https://cdn/a.jpg');
  });

  test('不可见帖子 post=null；缺 available 视为 false；available=true 但无 post 按不可见', () {
    expect(CollectedPost.fromJson({'postId': 5, 'available': false}).post, isNull);
    expect(CollectedPost.fromJson({'postId': 5}).available, isFalse);
    expect(CollectedPost.fromJson({'postId': 5, 'available': true}).showsAsAvailable, isFalse);
  });

  test('场所：place=null 时类型 / 封面 / 地址为 null，name 仍在', () {
    final hidden = CollectedPlace.fromJson({'placeToken': 't', 'available': false, 'name': 'Kopi'});
    expect(hidden.name, 'Kopi');
    expect(hidden.type, isNull);
    expect(hidden.coverUrl, isNull);
    expect(hidden.addressText, isNull);
    final visible = CollectedPlace.fromJson({
      'placeToken': 't',
      'available': true,
      'name': 'Kopi',
      'place': {'type': 'CAFE', 'coverUrl': 'https://cdn/p.jpg', 'addressText': 'Jl.'},
    });
    expect(visible.type, 'CAFE');
    expect(visible.coverUrl, 'https://cdn/p.jpg');
    expect(visible.addressText, 'Jl.');
    expect(CollectedPlace.fromJson({'placeToken': 't', 'name': 'x'}).available, isFalse);
  });

  test('tryFromJson：缺 postId / 空 placeToken → null（列表跳过该行）', () {
    expect(CollectedPost.tryFromJson({'available': true}), isNull);
    expect(CollectedPost.tryFromJson({'postId': 'x'}), isNull);
    expect(CollectedPost.tryFromJson({'postId': 3})!.postId, 3);
    expect(CollectedPlace.tryFromJson({'name': 'x'}), isNull);
    expect(CollectedPlace.tryFromJson({'placeToken': '', 'name': 'x'}), isNull);
    expect(CollectedPlace.tryFromJson({'placeToken': 't', 'name': 'x'})!.placeToken, 't');
  });
}
