import 'place_summary.dart';

/// 标记场所表单的草稿态（V1.3.0 batch-b1 Story 1.3）。
///
/// 🔴 **纯数据 + 纯判断，不碰 Widget** —— 这样「保存按钮什么时候才亮」（AC2）与
/// 「哪一条必填没满」（AC7）都是 L0 可测的，而不是只能靠模拟器上肉眼看。
///
/// 口径逐条对齐 FR-112.1 全集，与后端 `PlaceCreateRequest` 的 Bean Validation 同口径：
/// 名称必填 ≤80 · 类型单选 · 标签 ≥1 · 文字地址必填 ≤255 · 照片 1–9 张 · 描述选填 ≤200。
class PlaceFormDraft {
  const PlaceFormDraft({
    this.name = '',
    this.type,
    this.tags = const {},
    this.addressText = '',
    this.description = '',
    this.photoUrls = const [],
    this.latitude,
    this.longitude,
  });

  final String name;
  final PlaceType? type;

  /// 用 Set：同一个标签点两次是取消而不是加两遍。
  final Set<PlaceTag> tags;
  final String addressText;
  final String description;

  /// 已上传成功的公开桶 URL（1–9 张）。
  ///
  /// ⚠️ 只有**上传成功**的才进这里 —— 本地选中但还没传完的图不算「填好了」，
  /// 否则保存按钮会在图还没传完时就亮，点下去提交一批服务端取不到的 URL。
  final List<String> photoUrls;

  /// 位置（AC3：本 story 取当前定位；地图选点是 Story 1.4）。
  final double? latitude;
  final double? longitude;

  static const int nameMaxLength = 80;
  static const int addressMaxLength = 255;
  static const int descriptionMaxLength = 200;
  static const int photoMinCount = 1;
  static const int photoMaxCount = 9;

  bool get hasLocation => latitude != null && longitude != null;

  // ===== 逐字段判断（供内联错误用，AC7）=====

  bool get nameOk => name.trim().isNotEmpty && name.trim().length <= nameMaxLength;
  bool get typeOk => type != null;
  bool get tagsOk => tags.isNotEmpty;
  bool get addressOk =>
      addressText.trim().isNotEmpty && addressText.trim().length <= addressMaxLength;
  bool get descriptionOk => description.trim().length <= descriptionMaxLength;
  bool get photosOk =>
      photoUrls.length >= photoMinCount && photoUrls.length <= photoMaxCount;

  /// 「保存」能不能点（AC2）。
  ///
  /// 🔴 **位置也算必填**：没有坐标的场所在「按距离排序」里就是个永远排不进去的条目。
  /// AC3 要求未授权定位时明确提示需要位置 —— 那条提示的触发条件就是这里为 false。
  bool get canSubmit =>
      nameOk && typeOk && tagsOk && addressOk && descriptionOk && photosOk && hasLocation;

  /// 还能再加几张照片（0 = 已满 9 张）。
  int get remainingPhotoSlots => photoMaxCount - photoUrls.length;

  PlaceFormDraft copyWith({
    String? name,
    PlaceType? type,
    Set<PlaceTag>? tags,
    String? addressText,
    String? description,
    List<String>? photoUrls,
    double? latitude,
    double? longitude,
  }) {
    return PlaceFormDraft(
      name: name ?? this.name,
      type: type ?? this.type,
      tags: tags ?? this.tags,
      addressText: addressText ?? this.addressText,
      description: description ?? this.description,
      photoUrls: photoUrls ?? this.photoUrls,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
    );
  }

  /// 切换一个标签的选中态。
  PlaceFormDraft toggleTag(PlaceTag tag) {
    final next = Set<PlaceTag>.from(tags);
    if (!next.remove(tag)) {
      next.add(tag);
    }
    return copyWith(tags: next);
  }

  /// 追加已上传成功的照片，**超出 9 张的部分丢弃**（而不是报错）：
  /// 用户一次多选了 12 张时，前 9 张进来比整批失败有用。
  PlaceFormDraft addPhotos(List<String> urls) {
    final next = [...photoUrls, ...urls];
    return copyWith(
        photoUrls: next.length <= photoMaxCount ? next : next.sublist(0, photoMaxCount));
  }

  PlaceFormDraft removePhotoAt(int index) {
    if (index < 0 || index >= photoUrls.length) return this;
    final next = [...photoUrls]..removeAt(index);
    return copyWith(photoUrls: next);
  }
}
