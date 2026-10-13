import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/utils/media_permission.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/id_card_repository.dart';
import '../data/profile_repository.dart';
import '../domain/id_card.dart';
import '../domain/id_card_style.dart';
import 'id_card/id_card_form.dart';
import 'id_card/id_card_hd_flow.dart';
import 'id_card/id_card_pet_type_fields.dart';
import 'id_card/id_card_watermark.dart';
import 'id_card/ktp_fields.dart';
import 'id_card_detail_page.dart';

/// 独立建卡器（Story 6-7，决策④）——预览优先版。
///
/// V1.3.4 ktp Story 1.7：卡款由入口（「Pilih Kartu」网格）决定，页面**不再有切换卡款的 Tab**；
/// 卡面 / 水印 / 表单字段集 / 卡种与配色一律取注册表 [IdCardStyle]（AD-6 / AD-9）。
/// 「Ubah info」「Ganti foto」均**仅会话级、不落库**；必填（名字 / 物种 / 性别 / 生日，KTP 与居住证另加品种）
/// 缺失时「Buat Kartu」置灰并说明缺项。点「Buat Kartu」才 POST 落库并弹 HD 支付页，返回列表可见该卡。
class IdCardCreatePage extends ConsumerStatefulWidget {
  const IdCardCreatePage({super.key, this.initialStyle = IdCardStyle.ktp});

  /// 本页的卡款（Story 1.5 起由「Pilih Kartu」点格带入；名字沿用 1.5 已落地的对外契约）。
  /// 缺省 / 路由 query 非法时为 KTP（防御，正常入口只有网格）。
  final IdCardStyle initialStyle;

  /// 建卡页路由位置：`/profile/id-cards/create?style=<IdCardStyle.name>`（路由 builder 读 query，非法值回落 KTP）。
  static String locationFor(IdCardStyle style) => '/profile/id-cards/create?style=${style.name}';

  /// 路由 query `style` → 卡款；缺省 / 非法值回落 KTP（兼容不带参数的入口与旧深链）。
  static IdCardStyle styleFromQuery(String? style) => IdCardStyle.values.asNameMap()[style] ?? IdCardStyle.ktp;

  @override
  ConsumerState<IdCardCreatePage> createState() => _IdCardCreatePageState();
}

class _IdCardCreatePageState extends ConsumerState<IdCardCreatePage> {
  /// 会话级表单草稿（不落库）。
  IdCardFormDraft _draft = const IdCardFormDraft();
  String? _avatarUrl;
  bool _prefilled = false;
  bool _busy = false; // 上传/创建中

  IdCardStyle get _style => widget.initialStyle;

  @override
  void initState() {
    super.initState();
    // 预填当前档案值（会话级，可改；FR-142C.2）：名字 / 物种 / 品种 / 生日 / 性别 / 头像。
    // 档案无值不预选（性别 null、物种 null）；不再预填一句话介绍。失败则留空。
    ref.read(petProfileProvider.future).then((p) {
      if (!mounted || _prefilled || p == null) return;
      setState(() {
        _draft = IdCardFormDraft(
          name: p.name,
          petType: p.petType,
          breed: p.breed ?? '',
          birthday: p.birthday,
          gender: idCardGenderFromPetSex(p.sex),
        );
        _avatarUrl = p.avatarUrl;
        _prefilled = true;
      });
    }).catchError((_) {});
  }

  /// 会话级预览数据（未落库，序号未分配 → 客户端拼占位号，创建后以后端号为准）。
  IdCardData get _preview => IdCardData(
        generated: true,
        name: _emptyToNull(_draft.name),
        petType: _draft.petType,
        breed: _emptyToNull(_draft.breed),
        birthday: _draft.birthday,
        gender: _draft.gender,
        cardNo: buildPreviewCardNo(birthday: _draft.birthday, gender: _draft.gender, petType: _draft.petType),
        // 年份按 WIB（UTC+7）折算，与后端签发年一致（跨年时刻本地时区差异不影响预览）。
        passportNo: buildPreviewPassportNo(
            petType: _draft.petType, year: DateTime.now().toUtc().add(const Duration(hours: 7)).year),
        avatarUrl: _avatarUrl,
        birthCity: _emptyToNull(_draft.birthCity),
        address: _emptyToNull(_draft.address),
        occupation: _emptyToNull(_draft.occupation),
        maritalStatus: _emptyToNull(_draft.maritalStatus),
        school: _emptyToNull(_draft.school),
        faculty: _emptyToNull(_draft.faculty),
      );

  static String? _emptyToNull(String v) => v.trim().isEmpty ? null : v.trim();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final style = _style;
    final Size canvas = style.canvas;
    final double radius = style.canvasRadius;
    final Widget cardFront = style.buildFront(_preview);
    final missingText = idCardMissingText(l10n, idCardMissingFields(style, _draft));
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.idCardCreateTitle)),
      body: SafeArea(
        child: Column(
          children: [
            const SizedBox(height: 12),
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: AspectRatio(
                    aspectRatio: canvas.width / canvas.height,
                    // 防截图水印（bug 20260728-383）：建卡预览未付费即可见完整卡面，同样恒盖水印。
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        FittedBox(fit: BoxFit.contain, child: cardFront),
                        IdCardWatermark(canvas: canvas, canvasRadius: radius),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const ValueKey('idCardEditInfo'),
                      onPressed: _busy ? null : _editInfo,
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      label: Text(l10n.idCardEditInfo),
                      style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.mint,
                          side: const BorderSide(color: AppColors.lineViolet, width: 1.5),
                          padding: const EdgeInsets.symmetric(vertical: 12)),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const ValueKey('idCardEditPhoto'),
                      onPressed: _busy ? null : _editPhoto,
                      icon: const Icon(Icons.image_outlined, size: 18),
                      label: Text(l10n.idCardEditPhoto),
                      style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.mint,
                          side: const BorderSide(color: AppColors.lineViolet, width: 1.5),
                          padding: const EdgeInsets.symmetric(vertical: 12)),
                    ),
                  ),
                ],
              ),
            ),
            // 缺必填时按钮置灰，主页面必须给出原因（UI 稿 C4，T-14）——提示不能只藏在编辑抽屉里。
            if (missingText != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
                child: Text(missingText,
                    key: const ValueKey('idCardCreateMissingHint'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 12, color: AppColors.danger)),
              ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('idCardCreateSubmit'),
                  style: FilledButton.styleFrom(
                      backgroundColor: AppColors.mint,
                      padding: const EdgeInsets.symmetric(vertical: 14)),
                  onPressed: (missingText != null || _busy) ? null : _create,
                  child: _busy
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Text(l10n.idCardCreateSubmit,
                          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 修改信息（会话级）：按卡款字段集渲染的底部表单，确认后更新预览。**不落库**。
  Future<void> _editInfo() async {
    Analytics.capture('id_card_form_opened', {'card_type': _style.cardType});
    final result = await showModalBottomSheet<IdCardFormDraft>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (_) => _EditInfoSheet(style: _style, initial: _draft),
    );
    if (result != null) {
      setState(() {
        _draft = result;
        _prefilled = true; // 用户已确认的编辑优先：迟到的档案预填不得再覆盖
      });
    }
  }

  /// 修改图片（会话级）：选图 → 公开桶直传 → 设会话 avatarUrl。**不改档案、不落库**。
  Future<void> _editPhoto() async {
    final l10n = AppLocalizations.of(context);
    final useCase = ref.read(mediaUploadUseCaseProvider);
    setState(() => _busy = true);
    try {
      final bytes = await useCase.pickAndProcess(source: MediaSource.gallery, context: context);
      if (bytes == null) return; // 取消/权限拒
      final res = await useCase.uploadBytes(scope: MediaScope.public, bytes: bytes);
      if (mounted) setState(() => _avatarUrl = res.publicUrl ?? res.objectKey);
    } catch (_) {
      if (mounted) _toast(l10n.idCardCreateError);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 创建：POST 落库 → 弹 HD 付费抽屉 → 替换到新卡详情（V1.3.4 ktp Story 1.8，AD-10 / D-15 / D-16）。
  /// T-16：只提交本卡款表单内的字段，字段集外一律 null；intro 不再提交（PRD §3.4）。
  Future<void> _create() async {
    final l10n = AppLocalizations.of(context);
    final style = _style;
    final has = style.formFields.contains;
    setState(() => _busy = true);
    IdCard card;
    try {
      card = await ref.read(idCardRepositoryProvider).createCard(CreateIdCardRequest(
            name: _draft.name.trim(),
            petType: _draft.petType,
            breed: has(IdCardFormField.breed) ? _emptyToNull(_draft.breed) : null,
            birthday: _draft.birthday,
            gender: _draft.gender,
            avatarUrl: _avatarUrl,
            birthCity: has(IdCardFormField.birthCity) ? _emptyToNull(_draft.birthCity) : null,
            address: has(IdCardFormField.address) ? _emptyToNull(_draft.address) : null,
            occupation: has(IdCardFormField.occupation) ? _emptyToNull(_draft.occupation) : null,
            maritalStatus: has(IdCardFormField.maritalStatus) ? _emptyToNull(_draft.maritalStatus) : null,
            // 建卡即绑定单卡面；卡种 / 配色 wire 值取注册表（选 Bintang 就落 Bintang）。
            cardType: style.cardType,
            cardVariant: style.cardVariant,
            school: has(IdCardFormField.school) ? _emptyToNull(_draft.school) : null,
            faculty: has(IdCardFormField.faculty) ? _emptyToNull(_draft.faculty) : null,
          ));
      ref.invalidate(idCardListProvider);
    } catch (_) {
      if (mounted) {
        _toast(l10n.idCardCreateError);
        setState(() => _busy = false);
      }
      return;
    }
    // AD-12 / T-12：wire 值大写；非学生卡不带 card_variant 键。number_reused 取建卡响应（Epic 2 前恒 false）。
    Analytics.capture('id_card_created', {
      'card_type': style.cardType,
      if (style.cardVariant != null) 'card_variant': style.cardVariant!,
      'number_reused': card.numberReused,
    });
    if (!mounted) return;
    // 落库成功 → 弹付费抽屉（AD-10 共享函数，entry = create）。
    final result = await openIdCardHdPaywall(context, ref, card, entry: IdCardPaywallEntry.create);
    if (!mounted) return;
    // AD-10 / D-15：任何结局（关闭 / 成功 / 失败 / QRIS 未付）都替换到新卡详情，建卡页出栈，避免回建卡页重复建卡；
    // 失败提示已由共享函数先弹出。D-16：付费成功落到详情后同样自动弹保存 / 分享选单。
    context.pushReplacement(
        IdCardDetailPage.locationFor(card.id, autoExport: result == IdCardHdFlowResult.unlocked));
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}

/// 修改信息底部表单（会话级；确认返回草稿，不触后端）。字段集与顺序取自卡款（AD-9 / PRD §3.4）。
/// 结构：顶部把手 + 字段区（抽屉内滚动）+ 底部吸底「Selesai」；「Selesai」不拦截、始终可点。
class _EditInfoSheet extends StatefulWidget {
  const _EditInfoSheet({required this.style, required this.initial});
  final IdCardStyle style;
  final IdCardFormDraft initial;

  @override
  State<_EditInfoSheet> createState() => _EditInfoSheetState();
}

/// 当前展开的下拉（同一时刻最多一个）。
enum _Dropdown { species, breed }

class _EditInfoSheetState extends State<_EditInfoSheet> {
  late final TextEditingController _name;
  late final TextEditingController _breed;
  late final TextEditingController _birthCity;
  late final TextEditingController _address;
  late final TextEditingController _occupation;
  late final TextEditingController _maritalStatus;
  late final TextEditingController _school;
  late final TextEditingController _faculty;
  late String? _petType;
  late DateTime? _birthday;
  late String? _gender;
  _Dropdown? _open;

  @override
  void initState() {
    super.initState();
    final d = widget.initial;
    _name = TextEditingController(text: d.name);
    _breed = TextEditingController(text: d.breed);
    _birthCity = TextEditingController(text: d.birthCity);
    _address = TextEditingController(text: d.address);
    _occupation = TextEditingController(text: d.occupation);
    _maritalStatus = TextEditingController(text: d.maritalStatus);
    _school = TextEditingController(text: d.school);
    _faculty = TextEditingController(text: d.faculty);
    _petType = d.petType;
    _birthday = d.birthday;
    _gender = d.gender;
  }

  @override
  void dispose() {
    for (final c in [_name, _breed, _birthCity, _address, _occupation, _maritalStatus, _school, _faculty]) {
      c.dispose();
    }
    super.dispose();
  }

  IdCardFormDraft get _current => IdCardFormDraft(
        name: _name.text.trim(),
        petType: _petType,
        breed: _breed.text.trim(),
        gender: _gender,
        birthday: _birthday,
        birthCity: _birthCity.text.trim(),
        address: _address.text.trim(),
        occupation: _occupation.text.trim(),
        maritalStatus: _maritalStatus.text.trim(),
        school: _school.text.trim(),
        faculty: _faculty.text.trim(),
      );

  void _toggle(_Dropdown d) => setState(() => _open = _open == d ? null : d);

  /// 点其他字段时收起下拉。
  void _closeDropdown() {
    if (_open != null) setState(() => _open = null);
  }

  Future<void> _pickBirthday() async {
    _closeDropdown();
    final now = DateTime.now();
    final first = DateTime(now.year - 40);
    // 档案预填的生日可能落在可选范围外（更早 / 时钟偏差晚于今天），夹到范围内，避免 picker 断言。
    final initial = _birthday == null || _birthday!.isAfter(now)
        ? now
        : (_birthday!.isBefore(first) ? first : _birthday!);
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: first,
      lastDate: now,
    );
    if (picked != null) setState(() => _birthday = picked);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final style = widget.style;
    final fields = idCardOrderedFields(style);
    final maxHeight = MediaQuery.of(context).size.height * 0.9;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: maxHeight),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 12),
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(999)),
              ),
            ),
            const SizedBox(height: 12),
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (final (i, f) in fields.indexed) ...[
                      if (i > 0) const SizedBox(height: 14),
                      ..._field(l10n, style, f),
                    ],
                  ],
                ),
              ),
            ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    key: const ValueKey('idCardEditDone'),
                    style: FilledButton.styleFrom(
                        backgroundColor: AppColors.mint, padding: const EdgeInsets.symmetric(vertical: 14)),
                    onPressed: () => Navigator.of(context).pop(_current),
                    child: Text(l10n.idCardEditDone,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 单个字段（标签 + 控件 + 必要时红字）。必填标签带红色 `*`；表单内红字只有生日 / 物种 / 性别（T-15）。
  List<Widget> _field(AppLocalizations l10n, IdCardStyle style, IdCardFormField f) {
    Widget label(String text) =>
        idCardFieldRequired(style, f) ? _requiredLabel(text) : _label(text);
    switch (f) {
      case IdCardFormField.name:
        return [
          label(l10n.petProfileNameLabel),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('idCardEditName'),
            controller: _name,
            maxLength: 60,
            onTap: _closeDropdown,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
                hintText: l10n.petProfileNameHint, border: const OutlineInputBorder(), counterText: ''),
          ),
        ];
      case IdCardFormField.petType:
        return [
          label(l10n.petTypeLabel),
          const SizedBox(height: 6),
          IdCardSpeciesDropdown(
            petType: _petType,
            expanded: _open == _Dropdown.species,
            error: _petType == null,
            onToggle: () => _toggle(_Dropdown.species),
            onSelected: (t) => setState(() {
              if (t != _petType) _breed.clear(); // 改物种清空品种
              _petType = t;
              _open = null; // 选中即收起
            }),
          ),
          if (_petType == null) _errorText(l10n.idCardPetTypeRequired),
        ];
      case IdCardFormField.breed:
        return [
          label(l10n.petProfileBreedLabel),
          const SizedBox(height: 6),
          IdCardBreedDropdown(
            petType: _petType,
            controller: _breed,
            expanded: _open == _Dropdown.breed,
            onToggle: () => _toggle(_Dropdown.breed),
            onChanged: () => setState(() {}),
            onInputTap: _closeDropdown,
          ),
        ];
      case IdCardFormField.gender:
        return [
          label(l10n.idCardGenderLabel),
          const SizedBox(height: 6),
          Row(
            children: [
              for (final (i, (wire, text)) in <(String, String)>[
                ('MALE', l10n.idCardGenderMale),
                ('FEMALE', l10n.idCardGenderFemale),
                ('UNKNOWN', l10n.idCardGenderUnknown),
              ].indexed) ...[
                if (i > 0) const SizedBox(width: 8),
                Expanded(child: _genderChip(wire, text)),
              ],
            ],
          ),
          if (_gender == null) _errorText(l10n.idCardGenderRequired),
        ];
      case IdCardFormField.birthday:
        return [
          label(l10n.petProfileBirthdayLabel),
          const SizedBox(height: 6),
          InkWell(
            key: const ValueKey('idCardEditBirthday'),
            onTap: _pickBirthday,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
              decoration:
                  BoxDecoration(border: Border.all(color: AppColors.line), borderRadius: BorderRadius.circular(6)),
              child: Row(
                children: [
                  const Icon(Icons.cake_outlined, size: 18, color: AppColors.muted),
                  const SizedBox(width: 8),
                  Text(_birthday == null ? l10n.petProfileBirthdayPick : formatBirthday(context, _birthday!),
                      style: TextStyle(color: _birthday == null ? AppColors.muted : AppColors.ink)),
                ],
              ),
            ),
          ),
          if (_birthday == null) _errorText(l10n.idCardBirthdayRequired),
        ];
      // 卡面趣味字段（选填）：留空 = 卡面默认值，hint 直接展示各默认值（卡面设计常量，非 app-locale 文案）。
      case IdCardFormField.birthCity:
        return [
          label(l10n.idCardBirthCityLabel),
          const SizedBox(height: 6),
          _funField('idCardEditBirthCity', _birthCity, KtpDefaults.tempatKota, 20),
        ];
      case IdCardFormField.address:
        return [
          label(l10n.idCardAddressLabel),
          const SizedBox(height: 6),
          _funField('idCardEditAddress', _address, KtpDefaults.alamat, 48),
        ];
      case IdCardFormField.occupation:
        return [
          label(l10n.idCardOccupationLabel),
          const SizedBox(height: 6),
          _funField('idCardEditOccupation', _occupation, KtpDefaults.pekerjaan, 30),
        ];
      case IdCardFormField.maritalStatus:
        return [
          label(l10n.idCardMaritalLabel),
          const SizedBox(height: 6),
          _funField('idCardEditMarital', _maritalStatus, KtpDefaults.statusPerkawinan, 20),
        ];
      case IdCardFormField.school:
        return [
          label(l10n.idCardSchoolLabel),
          const SizedBox(height: 6),
          _funField('idCardEditSchool', _school, 'TAILTOPIA ACADEMY', 20),
        ];
      case IdCardFormField.faculty:
        return [
          label(l10n.idCardFacultyLabel),
          const SizedBox(height: 6),
          _funField('idCardEditFaculty', _faculty, 'FAKULTAS KEBAHAGIAAN', 20),
        ];
    }
  }

  Widget _errorText(String text) => Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Text(text, style: const TextStyle(fontSize: 11, color: AppColors.danger)),
      );

  /// 性别单选 chip（随表单控件风格：选中紫底白字，未选卡底描边）。
  Widget _genderChip(String wire, String label) {
    final selected = _gender == wire;
    return GestureDetector(
      key: ValueKey('idCardGender_$wire'),
      onTap: () => setState(() {
        _gender = wire;
        _open = null;
      }),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: selected ? AppColors.mint : AppColors.surface,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: 1.5),
        ),
        child: Text(label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
                color: selected ? Colors.white : AppColors.ink2, fontWeight: FontWeight.w600, fontSize: 13)),
      ),
    );
  }

  /// 趣味字段输入框：hint 展示卡面默认值（留空即用默认）。
  Widget _funField(String key, TextEditingController controller, String hint, int maxLength) => TextField(
        key: ValueKey(key),
        controller: controller,
        maxLength: maxLength,
        onTap: _closeDropdown,
        decoration: InputDecoration(hintText: hint, border: const OutlineInputBorder(), counterText: ''),
      );

  Widget _label(String text) =>
      Text(text, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.ink2));

  /// 必填字段标签：`_label` + 红色 `*`（必填视觉标记，非文案）。
  Widget _requiredLabel(String text) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _label(text),
          const Text(' *', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.danger)),
        ],
      );
}
