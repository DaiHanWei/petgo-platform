/// 多宠庆祝页（V1.4.2 social-topic Story 2.3 · UI B2）宠物行里的一只宠物：头像 + 名字。
///
/// 由调用方在**弹出前**从 `petListProvider` 取好（ACTIVE、建档顺序、最多 5 只）传给庆祝页 ——
/// 庆祝层是 root navigator 上的 dialog，宿主页可能先销毁，不在庆祝 widget 内 watch。
class CelebrationPet {
  const CelebrationPet({required this.name, this.avatarUrl, this.petType});

  final String name;
  final String? avatarUrl;

  /// `CAT` / `DOG` / 其它（无头像时决定物种默认图）。
  final String? petType;
}
