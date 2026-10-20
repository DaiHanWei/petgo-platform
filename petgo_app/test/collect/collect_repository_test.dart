import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/collect/domain/collect_target.dart';

/// V1.4.0 ④ Story 2.3 · AC1：收藏数据层纯单元。
void main() {
  test('CollectResult.fromJson：真 / 假 / 缺键', () {
    expect(CollectResult.fromJson({'collected': true}).collected, isTrue);
    expect(CollectResult.fromJson({'collected': false}).collected, isFalse);
    expect(CollectResult.fromJson({}).collected, isFalse);
    expect(CollectResult.fromJson(null).collected, isFalse);
  });

  test('ApiPaths：帖子 / 场所收藏路径；场所 token 被 encode', () {
    expect(ApiPaths.contentPostCollect(5), '/api/v1/content-posts/5/collect');
    expect(ApiPaths.placeCollect('a b'), '/api/v1/places/a%20b/collect');
  });

  test('CollectTarget：savedLocation 与 fromSavedParam', () {
    expect(CollectTarget.savedLocation(CollectTarget.post), '/me?saved=post');
    expect(CollectTarget.savedLocation(CollectTarget.place), '/me?saved=place');
    expect(CollectTarget.fromSavedParam('post'), CollectTarget.post);
    expect(CollectTarget.fromSavedParam('place'), CollectTarget.place);
    expect(CollectTarget.fromSavedParam('x'), isNull);
    expect(CollectTarget.fromSavedParam(null), isNull);
  });
}
