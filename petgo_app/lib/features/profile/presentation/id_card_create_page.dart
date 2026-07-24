import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/qr_payment_sheet.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/id_card_repository.dart';
import '../data/profile_repository.dart';
import '../domain/id_card.dart';
import 'id_card/hd_paywall_sheet.dart';
import 'id_card/ktp_card.dart';
import 'id_card/ktp_fields.dart';
import 'id_card/passport_card.dart';
import 'id_card/student_card.dart';
import 'widgets/pet_form_fields.dart';

/// 独立建卡器（Story 6-7，决策④）——预览优先版：
/// 点「New ID Card」进本页 → 卡面按当前档案信息渲染预览（KTP/Paspor/Pelajar 三 Tab 可切）。
/// 「修改信息」「修改图片」均**仅会话级、不落库**；点「创建」才 POST 落库并弹 HD 支付页，
/// 返回列表可见该待支付/已支付卡。
class IdCardCreatePage extends ConsumerStatefulWidget {
  const IdCardCreatePage({super.key});

  @override
  ConsumerState<IdCardCreatePage> createState() => _IdCardCreatePageState();
}

class _IdCardCreatePageState extends ConsumerState<IdCardCreatePage> {
  String _name = '';
  String? _petType;
  String _breed = '';
  DateTime? _birthday;
  String _intro = '';
  String? _avatarUrl;
  bool _prefilled = false;
  int _styleIndex = 0; // 0=KTP, 1=Paspor, 2=Pelajar
  bool _busy = false; // 上传/创建中

  @override
  void initState() {
    super.initState();
    // 默认预填当前档案值（会话级，可改）。失败则留空。
    ref.read(petProfileProvider.future).then((p) {
      if (!mounted || _prefilled || p == null) return;
      setState(() {
        _name = p.name;
        _petType = p.petType;
        _breed = p.breed ?? '';
        _birthday = p.birthday;
        _intro = p.intro ?? '';
        _avatarUrl = p.avatarUrl;
        _prefilled = true;
      });
    }).catchError((_) {});
  }

  /// 会话级预览数据（未落库，serialId 未分配 → 预览用占位号）。
  IdCardData get _preview => IdCardData(
        generated: true,
        name: _name.trim().isEmpty ? null : _name.trim(),
        petType: _petType,
        breed: _breed.trim().isEmpty ? null : _breed.trim(),
        birthday: _birthday,
        avatarUrl: _avatarUrl,
        intro: _intro.trim().isEmpty ? null : _intro.trim(),
      );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (Size canvas, Widget cardFront) = switch (_styleIndex) {
      1 => (kPassportCardCanvas, PassportCardFront(fields: buildPassportFields(_preview))),
      2 => (kStudentCardCanvas, StudentCardFront(fields: buildStudentFields(_preview))),
      _ => (kIdCardCanvas, KtpCardFront(fields: buildKtpFields(_preview, KtpEdits.empty))),
    };
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.idCardCreateTitle)),
      body: SafeArea(
        child: Column(
          children: [
            _styleSwitcher(l10n),
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: AspectRatio(
                    aspectRatio: canvas.width / canvas.height,
                    child: FittedBox(fit: BoxFit.contain, child: cardFront),
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
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('idCardCreateSubmit'),
                  style: FilledButton.styleFrom(
                      backgroundColor: AppColors.mint,
                      padding: const EdgeInsets.symmetric(vertical: 14)),
                  onPressed: (_name.trim().isEmpty || _busy) ? null : _create,
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

  Widget _styleSwitcher(AppLocalizations l10n) {
    final labels = [l10n.idCardStyleKtp, l10n.idCardStylePaspor, l10n.idCardStylePelajar];
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Row(
        children: [
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(right: i < labels.length - 1 ? 8 : 0),
                child: _styleTab(labels[i], i),
              ),
            ),
        ],
      ),
    );
  }

  Widget _styleTab(String label, int index) {
    final selected = _styleIndex == index;
    return GestureDetector(
      key: ValueKey('idCardStyleTab_$index'),
      onTap: () => setState(() => _styleIndex = index),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: selected ? AppColors.mint : AppColors.card,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: 1.5),
        ),
        child: Text(label,
            style: TextStyle(
                color: selected ? Colors.white : AppColors.ink2,
                fontWeight: FontWeight.w600,
                fontSize: 14)),
      ),
    );
  }

  /// 修改信息（会话级）：底部表单编辑 name/type/breed/dob/intro，确认后更新预览。**不落库**。
  Future<void> _editInfo() async {
    final result = await showModalBottomSheet<_InfoDraft>(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (_) => _EditInfoSheet(
        initial: _InfoDraft(
            name: _name, petType: _petType, breed: _breed, birthday: _birthday, intro: _intro),
      ),
    );
    if (result != null) {
      setState(() {
        _name = result.name;
        _petType = result.petType;
        _breed = result.breed;
        _birthday = result.birthday;
        _intro = result.intro;
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

  /// 创建：POST 落库 → 弹 HD 支付页 → 返回列表（可见待支付/已支付卡）。
  Future<void> _create() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _busy = true);
    IdCard card;
    try {
      card = await ref.read(idCardRepositoryProvider).createCard(CreateIdCardRequest(
            name: _name.trim(),
            petType: _petType,
            breed: _breed.trim().isEmpty ? null : _breed.trim(),
            birthday: _birthday,
            avatarUrl: _avatarUrl,
            intro: _intro.trim().isEmpty ? null : _intro.trim(),
          ));
      ref.invalidate(idCardListProvider);
    } catch (_) {
      if (mounted) {
        _toast(l10n.idCardCreateError);
        setState(() => _busy = false);
      }
      return;
    }
    if (!mounted) return;
    // 落库成功 → 弹支付页（HD 解锁）。无论支付与否，卡已在列表。
    await _openPayment(card);
    if (mounted) context.pop(); // 返回列表
  }

  /// HD 付费解锁（复用详情页范式）：选渠道 → PawCoin 直扣或现金 QR。
  Future<void> _openPayment(IdCard card) async {
    int balance = 0;
    try {
      balance = (await ref.read(pawCoinProvider.future)).balance;
    } catch (_) {}
    if (!mounted) return;
    final channel = await showModalBottomSheet<HdPayChannel>(
      context: context,
      backgroundColor: AppColors.card,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (_) => HdPaywallSheet(
          petName: card.name, serialId: card.serialId, avatarUrl: card.avatarUrl, balance: balance),
    );
    if (channel == null || !mounted) return;
    final l10n = AppLocalizations.of(context);
    try {
      final res = await ref.read(idCardRepositoryProvider).purchaseHdForCard(card.id, channel);
      if (res.unlocked) {
        ref.invalidate(idCardListProvider);
        _toast(l10n.idCardHdUnlockedToast);
      } else if ((res.payload?.isNotEmpty ?? false) && mounted) {
        await showQrPaymentSheet(
          context,
          payload: res.payload!,
          orderRef: res.paymentToken,
          pollPaid: () async {
            final c = await ref.refresh(idCardDetailProvider(card.id).future);
            return c.hdUnlocked;
          },
        );
        ref.invalidate(idCardListProvider);
      } else {
        _toast(l10n.idCardHdQrisPending);
      }
    } on DioException catch (e) {
      _toast(e.response?.statusCode == 409
          ? l10n.idCardHdInsufficientBalance
          : l10n.idCardHdPurchaseError);
    } catch (_) {
      _toast(l10n.idCardHdPurchaseError);
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }
}

/// 修改信息草稿（会话级）。
class _InfoDraft {
  const _InfoDraft(
      {required this.name,
      required this.petType,
      required this.breed,
      required this.birthday,
      required this.intro});
  final String name;
  final String? petType;
  final String breed;
  final DateTime? birthday;
  final String intro;
}

/// 修改信息底部表单（会话级；确认返回草稿，不触后端）。
class _EditInfoSheet extends StatefulWidget {
  const _EditInfoSheet({required this.initial});
  final _InfoDraft initial;

  @override
  State<_EditInfoSheet> createState() => _EditInfoSheetState();
}

class _EditInfoSheetState extends State<_EditInfoSheet> {
  late final TextEditingController _name;
  late final TextEditingController _breed;
  late final TextEditingController _intro;
  late String? _petType;
  late DateTime? _birthday;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.initial.name);
    _breed = TextEditingController(text: widget.initial.breed);
    _intro = TextEditingController(text: widget.initial.intro);
    _petType = widget.initial.petType;
    _birthday = widget.initial.birthday;
  }

  @override
  void dispose() {
    _name.dispose();
    _breed.dispose();
    _intro.dispose();
    super.dispose();
  }

  Future<void> _pickBirthday() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _birthday ?? now,
      firstDate: DateTime(now.year - 40),
      lastDate: now,
    );
    if (picked != null) setState(() => _birthday = picked);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: EdgeInsets.only(
          left: 20, right: 20, top: 18, bottom: MediaQuery.of(context).viewInsets.bottom + 24),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration:
                    BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(999)),
              ),
            ),
            const SizedBox(height: 16),
            _label(l10n.petProfileNameLabel),
            const SizedBox(height: 6),
            TextField(
              key: const ValueKey('idCardEditName'),
              controller: _name,
              maxLength: 60,
              decoration: InputDecoration(
                  hintText: l10n.petProfileNameHint,
                  border: const OutlineInputBorder(),
                  counterText: ''),
            ),
            const SizedBox(height: 14),
            _label(l10n.petTypeLabel),
            const SizedBox(height: 6),
            SpeciesField(
                petType: _petType,
                onChanged: (t) => setState(() {
                      _petType = t;
                      _breed.clear();
                    })),
            const SizedBox(height: 14),
            _label(l10n.petProfileBreedLabel),
            const SizedBox(height: 6),
            BreedField(petType: _petType, controller: _breed, onChanged: () => setState(() {})),
            const SizedBox(height: 14),
            _label(l10n.petProfileBirthdayLabel),
            const SizedBox(height: 6),
            InkWell(
              key: const ValueKey('idCardEditBirthday'),
              onTap: _pickBirthday,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
                decoration: BoxDecoration(
                    border: Border.all(color: AppColors.line),
                    borderRadius: BorderRadius.circular(6)),
                child: Row(
                  children: [
                    const Icon(Icons.cake_outlined, size: 18, color: AppColors.muted),
                    const SizedBox(width: 8),
                    Text(
                        _birthday == null
                            ? l10n.petProfileBirthdayPick
                            : formatBirthday(context, _birthday!),
                        style: TextStyle(
                            color: _birthday == null ? AppColors.muted : AppColors.ink)),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),
            _label(l10n.petProfileBioLabel),
            const SizedBox(height: 6),
            TextField(
              key: const ValueKey('idCardEditIntro'),
              controller: _intro,
              maxLength: 30,
              decoration:
                  InputDecoration(hintText: l10n.petProfileIntro, border: const OutlineInputBorder()),
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('idCardEditDone'),
                style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    padding: const EdgeInsets.symmetric(vertical: 14)),
                onPressed: () => Navigator.of(context).pop(_InfoDraft(
                    name: _name.text.trim(),
                    petType: _petType,
                    breed: _breed.text.trim(),
                    birthday: _birthday,
                    intro: _intro.text.trim())),
                child: Text(l10n.idCardEditDone,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _label(String text) => Text(text,
      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.ink2));
}
