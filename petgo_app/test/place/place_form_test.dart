import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/domain/place_form.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

/// V1.3.0 batch-b1 Story 1.3 · L0：表单完整性口径（AC1/AC2 的可静态验证部分）。
///
/// AC2「必填未满时保存按钮是灰的」在界面上是 L2，但**判据本身是纯逻辑** ——
/// 放进 [PlaceFormDraft.canSubmit] 之后就能在这里逐条钉死，而不是只能靠模拟器肉眼看。
PlaceFormDraft _complete() => const PlaceFormDraft(
      name: 'Kopi Kayu Manis',
      type: PlaceType.cafe,
      tags: {PlaceTag.petsAllowedInside},
      addressText: 'Jl. Senopati No.75',
      photoUrls: ['https://cdn/a.jpg'],
      latitude: -6.235,
      longitude: 106.81,
    );

void main() {
  test('全部必填填满 → 可提交', () {
    expect(_complete().canSubmit, isTrue);
  });

  test('空草稿 → 不可提交', () {
    expect(const PlaceFormDraft().canSubmit, isFalse);
  });

  group('🔴 每一项必填都真的是必填（漏一项就等于那一项形同虚设）', () {
    test('名称', () {
      expect(_complete().copyWith(name: '').canSubmit, isFalse);
      expect(_complete().copyWith(name: '   ').canSubmit, isFalse,
          reason: '只有空格不算填了');
    });

    test('名称超 80 字', () {
      expect(_complete().copyWith(name: 'x' * 81).canSubmit, isFalse);
      expect(_complete().copyWith(name: 'x' * 80).canSubmit, isTrue);
    });

    test('类型', () {
      // copyWith 的 null 表示「不改」，所以用构造器造一个没类型的草稿。
      const d = PlaceFormDraft(
        name: 'A',
        tags: {PlaceTag.petMenu},
        addressText: 'Jl. A',
        photoUrls: ['https://cdn/a.jpg'],
        latitude: -6.2,
        longitude: 106.8,
      );
      expect(d.typeOk, isFalse);
      expect(d.canSubmit, isFalse);
    });

    test('标签至少一个', () {
      expect(_complete().copyWith(tags: {}).canSubmit, isFalse);
    });

    test('文字地址', () {
      expect(_complete().copyWith(addressText: '').canSubmit, isFalse);
      expect(_complete().copyWith(addressText: 'x' * 256).canSubmit, isFalse);
    });

    test('照片至少一张', () {
      expect(_complete().copyWith(photoUrls: []).canSubmit, isFalse);
    });

    test('🔴 位置也是必填', () {
      const d = PlaceFormDraft(
        name: 'A',
        type: PlaceType.cafe,
        tags: {PlaceTag.petMenu},
        addressText: 'Jl. A',
        photoUrls: ['https://cdn/a.jpg'],
      );
      expect(d.hasLocation, isFalse);
      expect(d.canSubmit, isFalse,
          reason: '没有坐标的场所在「按距离排序」里永远排不进去');
    });
  });

  group('描述是选填', () {
    test('空描述照样可提交', () {
      expect(_complete().copyWith(description: '').canSubmit, isTrue);
    });

    test('但超 200 字不行', () {
      expect(_complete().copyWith(description: 'x' * 201).canSubmit, isFalse);
      expect(_complete().copyWith(description: 'x' * 200).canSubmit, isTrue);
    });
  });

  group('标签切换', () {
    test('点一次选中，再点一次取消', () {
      var d = const PlaceFormDraft();
      d = d.toggleTag(PlaceTag.petMenu);
      expect(d.tags, {PlaceTag.petMenu});
      d = d.toggleTag(PlaceTag.petMenu);
      expect(d.tags, isEmpty);
    });

    test('多选彼此独立', () {
      final d = const PlaceFormDraft()
          .toggleTag(PlaceTag.petMenu)
          .toggleTag(PlaceTag.leashRequired);
      expect(d.tags, {PlaceTag.petMenu, PlaceTag.leashRequired});
    });
  });

  group('照片 1–9 张', () {
    test('9 张可提交，第 10 张进不来', () {
      final nine = List.generate(9, (i) => 'https://cdn/$i.jpg');
      final d = _complete().copyWith(photoUrls: nine);
      expect(d.canSubmit, isTrue);
      expect(d.remainingPhotoSlots, 0);

      // 一次多选了 12 张：前 9 张进来比整批失败有用。
      final overflow = const PlaceFormDraft()
          .addPhotos(List.generate(12, (i) => 'https://cdn/$i.jpg'));
      expect(overflow.photoUrls, hasLength(9));
    });

    test('追加不会越过上限', () {
      final d = _complete()
          .copyWith(photoUrls: List.generate(8, (i) => 'u$i'))
          .addPhotos(['a', 'b', 'c']);
      expect(d.photoUrls, hasLength(9));
      expect(d.photoUrls.last, 'a', reason: '截断发生在尾部，先来的先进');
    });

    test('删除一张后又能再加', () {
      final d = _complete().copyWith(photoUrls: List.generate(9, (i) => 'u$i'));
      expect(d.remainingPhotoSlots, 0);
      expect(d.removePhotoAt(0).remainingPhotoSlots, 1);
      expect(d.removePhotoAt(0).photoUrls.first, 'u1');
    });

    test('越界删除是 no-op（不抛）', () {
      final d = _complete();
      expect(d.removePhotoAt(99).photoUrls, d.photoUrls);
      expect(d.removePhotoAt(-1).photoUrls, d.photoUrls);
    });
  });

  /// 🔴 7 类型 + 6 标签是**全集**（AC1）。UI 稿 A5 只画了几个是示意省略。
  /// 这条守的是「实现按全集」—— 表单直接遍历枚举，所以枚举少一个值就等于表单少一个选项。
  test('类型与标签的枚举就是全集（7 / 6）', () {
    expect(PlaceType.values, hasLength(7));
    expect(PlaceTag.values, hasLength(6));
  });
}
