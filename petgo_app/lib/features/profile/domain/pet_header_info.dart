/// Diary 页头需要的宠物信息（V1.1.6 Story 2.3）。
///
/// ## 为什么要有这个类
/// 页头组件原先直接吃作者态的 `PetProfile`，而访客拿到的档案**字段少得多**
/// （没有内部 id、没有分享 token —— 服务端根本不下发）。
/// 硬把访客数据塞进 `PetProfile` 就得给 id / cardToken 编假值，
/// **而假值迟早会在某个地方被当真**。
///
/// 所以把「页头到底需要哪几样」单独写出来：两种档案各自产出它，页头只认这一个类型。
/// 这也顺带说明了一件事 —— 页头本来就不需要 id 和 token。
class PetHeaderInfo {
  const PetHeaderInfo({
    required this.name,
    this.petType,
    this.avatarUrl,
    this.breed,
    this.birthday,
    this.sex,
    this.intro,
  });

  final String name;
  final String? petType;
  final String? avatarUrl;
  final String? breed;
  final DateTime? birthday;
  final String? sex;
  final String? intro;
}
