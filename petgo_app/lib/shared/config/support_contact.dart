/// 客服联系方式（Story 3-1 / AD-S8）。后端 `GET /api/v1/support/contact` 的镜像。
library;

/// 两种号码形态。
///
/// | 形态 | 值 | 用途 |
/// |---|---|---|
/// | [whatsappNumber] | `081290906953` | 展示、复制到剪贴板（印尼人认这个写法） |
/// | [whatsappE164] | `+6281290906953` | 深链（Story 3-3 会去掉 `+` 拼 `wa.me/6281290906953`） |
class SupportContact {
  const SupportContact({
    required this.whatsappNumber,
    required this.whatsappE164,
    required this.email,
  });

  final String whatsappNumber;

  /// 标准 E.164，**带 `+`**。`wa.me` 的路径段不要 `+`，剥 `+` 是 URL 构造方的事。
  final String whatsappE164;

  final String email;

  /// 🔴 **两个号码字段必须一起取或一起兜底**，不能各自独立回退。
  ///
  /// 独立回退会造出「展示的是新号、深链拨的是旧号」这种组合 —— 用户看到一个号码、
  /// 点下去联系到另一个人，比整条用旧值糟得多。邮箱与号码无耦合，可以独立兜底。
  factory SupportContact.fromJson(Map<String, dynamic> j) {
    final number = _nonBlank(j['whatsappNumber']);
    final e164 = _nonBlank(j['whatsappE164']);
    final bothPresent = number != null && e164 != null;
    return SupportContact(
      whatsappNumber: bothPresent ? number : kFallbackSupportContact.whatsappNumber,
      whatsappE164: bothPresent ? e164 : kFallbackSupportContact.whatsappE164,
      email: _nonBlank(j['email']) ?? kFallbackSupportContact.email,
    );
  }

  /// 空串按「没有」处理 —— 弹窗里显示一个空号码比显示旧号码糟得多。
  static String? _nonBlank(Object? v) {
    final s = v?.toString();
    return (s == null || s.isEmpty) ? null : s;
  }

  @override
  bool operator ==(Object other) =>
      other is SupportContact &&
      other.whatsappNumber == whatsappNumber &&
      other.whatsappE164 == whatsappE164 &&
      other.email == email;

  @override
  int get hashCode => Object.hash(whatsappNumber, whatsappE164, email);
}

/// 🔴 **兜底常量**：请求失败 / 超时 / 后端没升级时用它，弹窗**永不出现空值或占位符**。
///
/// 值与后端 `SupportContactProvider.Default` 及迁移种子一致（SD-10 已定用此号）。
/// 它是全 App **唯一**的号码字面量 —— Story 3-3 的深链也读这里，别再复制一份，
/// 复制一份就又变回「换号要改两处」。
const SupportContact kFallbackSupportContact = SupportContact(
  whatsappNumber: '081290906953',
  whatsappE164: '+6281290906953',
  email: 'cs@tailtopia.id',
);
