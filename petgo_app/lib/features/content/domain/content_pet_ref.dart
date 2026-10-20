/// 帖子关联的宠物（V1.4.0 multi-pet Story 3.3 · AC4.1，消费 Story 3.1 详情响应的 `pets[]`，
/// 后端 `ContentPetView(petId, name, avatarUrl, petType)`）。
///
/// 只有 Diary 且有关联时后端才下发，**按宠物创建顺序**；缺省 / 非数组 → 空表。
/// ⚠️ [name] 是用户数据，只用于渲染，不得写进日志 / 埋点（§5）。
class ContentPetRef {
  const ContentPetRef({required this.petId, required this.name, this.avatarUrl, this.petType, this.birthday});

  final int petId;
  final String name;
  final String? avatarUrl;
  final String? petType;

  /// 生日（V1.4.0 L2 第二轮，后端可空追加）：详情页送礼 A7 选宠物行显示年龄；旧后端 / 未填 → null。
  final DateTime? birthday;

  static List<ContentPetRef> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    final out = <ContentPetRef>[];
    for (final e in raw) {
      if (e is! Map) continue;
      final id = e['petId'];
      if (id is! num) continue;
      final avatar = e['avatarUrl']?.toString();
      out.add(ContentPetRef(
        petId: id.toInt(),
        name: e['name']?.toString() ?? '',
        avatarUrl: (avatar == null || avatar.isEmpty) ? null : avatar,
        petType: e['petType']?.toString(),
        birthday: e['birthday'] is String ? DateTime.tryParse(e['birthday'] as String) : null,
      ));
    }
    return List.unmodifiable(out);
  }
}
