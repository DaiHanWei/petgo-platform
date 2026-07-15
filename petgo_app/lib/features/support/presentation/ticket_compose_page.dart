import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../media/data/oss_uploader.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/support_repository.dart';
import '../domain/support_ticket.dart';
import 'support_l10n.dart';

/// 提交投诉工单页（Story 4.2）。主题(选填)/正文(必填)/联系方式(必填)/标签(多选)/≤5 图。
/// 附件复用 media 私密桶直传（照 consult_case_form_page）。提交成功 → 详情页。
class TicketComposePage extends ConsumerStatefulWidget {
  const TicketComposePage({super.key});

  @override
  ConsumerState<TicketComposePage> createState() => _TicketComposePageState();
}

class _PickedPhoto {
  const _PickedPhoto(this.bytes, this.objectKey);
  final Uint8List bytes;
  final String objectKey;
}

class _TicketComposePageState extends ConsumerState<TicketComposePage> {
  static const int _maxPhotos = 5;

  final TextEditingController _subject = TextEditingController();
  final TextEditingController _body = TextEditingController();
  final TextEditingController _contactValue = TextEditingController();
  final List<_PickedPhoto> _photos = [];
  final Set<TicketLabelType> _labels = {};
  ContactType _contactType = ContactType.whatsapp;
  bool _needContact = true;
  bool _uploading = false;
  bool _submitting = false;

  @override
  void dispose() {
    _subject.dispose();
    _body.dispose();
    _contactValue.dispose();
    super.dispose();
  }

  bool get _canSubmit =>
      _body.text.trim().isNotEmpty && _contactValue.text.trim().isNotEmpty && !_uploading && !_submitting;

  void _toast(String msg) {
    if (!mounted) return;
    showAppToast(context, msg);
  }

  Future<void> _addPhoto() async {
    if (_photos.length >= _maxPhotos || _uploading) return;
    final l10n = AppLocalizations.of(context);
    final source = await _pickSourceSheet();
    if (source == null || !mounted) return;
    setState(() => _uploading = true);
    try {
      final useCase = ref.read(mediaUploadUseCaseProvider);
      final bytes = await useCase.pickAndProcess(source: source, context: context);
      if (bytes == null || !mounted) return;
      final OssUploadResult res = await useCase.uploadBytes(scope: MediaScope.private, bytes: bytes);
      if (!mounted) return;
      setState(() => _photos.add(_PickedPhoto(bytes, res.objectKey)));
    } catch (_) {
      _toast(l10n.ticketSubmitFailed);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<MediaSource?> _pickSourceSheet() {
    final l10n = AppLocalizations.of(context);
    return showModalBottomSheet<MediaSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined),
              title: Text(l10n.triagePhotoFromCamera),
              onTap: () => Navigator.pop(ctx, MediaSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: Text(l10n.triagePhotoFromGallery),
              onTap: () => Navigator.pop(ctx, MediaSource.gallery),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (!_canSubmit) return;
    setState(() => _submitting = true);
    final l10n = AppLocalizations.of(context);
    try {
      final ticket = await ref.read(supportRepositoryProvider).createTicket(
            subject: _subject.text,
            body: _body.text,
            contactType: _contactType,
            contactValue: _contactValue.text,
            needContact: _needContact,
            labels: _labels.toList(),
            attachmentObjectKeys: _photos.map((p) => p.objectKey).toList(),
          );
      if (!mounted) return;
      _toast(l10n.ticketSubmitSuccess);
      context.pushReplacement('/me/support-tickets/${ticket.ticketToken}');
    } on DioException {
      _toast(l10n.ticketSubmitFailed);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.surface, title: Text(l10n.ticketComposeTitle)),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(AppSpacing.md),
                children: [
                  _label(l10n.ticketBodyLabel),
                  _boxedField(_body, hint: l10n.ticketBodyHint, maxLines: 4),
                  const SizedBox(height: AppSpacing.md),
                  _label(l10n.ticketSubjectLabel),
                  _boxedField(_subject, hint: l10n.ticketSubjectHint, maxLines: 1),
                  const SizedBox(height: AppSpacing.md),
                  _label(l10n.ticketContactTypeLabel),
                  const SizedBox(height: AppSpacing.sm),
                  _contactTypeSelector(l10n),
                  const SizedBox(height: AppSpacing.sm),
                  _boxedField(_contactValue, hint: l10n.ticketContactValueHint, maxLines: 1),
                  const SizedBox(height: AppSpacing.md),
                  _label(l10n.ticketLabelsLabel),
                  const SizedBox(height: AppSpacing.sm),
                  _labelChips(l10n),
                  const SizedBox(height: AppSpacing.md),
                  _label(l10n.ticketPhotosLabel),
                  const SizedBox(height: AppSpacing.sm),
                  _photoRow(),
                  const SizedBox(height: AppSpacing.md),
                  SwitchListTile(
                    key: const ValueKey('ticketNeedContact'),
                    contentPadding: EdgeInsets.zero,
                    value: _needContact,
                    activeThumbColor: AppColors.mint,
                    title: Text(l10n.ticketNeedContact, style: AppTypography.body),
                    onChanged: (v) => setState(() => _needContact = v),
                  ),
                ],
              ),
            ),
            _submitBar(l10n),
          ],
        ),
      ),
    );
  }

  Widget _label(String text) => Text(text,
      style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5));

  Widget _boxedField(TextEditingController c, {required String hint, required int maxLines}) {
    return Container(
      margin: const EdgeInsets.only(top: AppSpacing.sm),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: TextField(
        controller: c,
        maxLines: maxLines,
        onChanged: (_) => setState(() {}),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: const TextStyle(color: AppColors.muted, fontSize: 13, height: 1.6),
          border: InputBorder.none,
        ),
      ),
    );
  }

  Widget _contactTypeSelector(AppLocalizations l10n) {
    return SegmentedButton<ContactType>(
      segments: [
        ButtonSegment(value: ContactType.whatsapp, label: Text(contactTypeLabel(l10n, ContactType.whatsapp))),
        ButtonSegment(value: ContactType.email, label: Text(contactTypeLabel(l10n, ContactType.email))),
      ],
      selected: {_contactType},
      showSelectedIcon: false,
      onSelectionChanged: (s) => setState(() => _contactType = s.first),
    );
  }

  Widget _labelChips(AppLocalizations l10n) {
    return Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: [
        for (final t in TicketLabelType.values)
          FilterChip(
            label: Text(ticketLabelText(l10n, t)),
            selected: _labels.contains(t),
            selectedColor: AppColors.mintTint,
            checkmarkColor: AppColors.mint600,
            onSelected: (on) => setState(() => on ? _labels.add(t) : _labels.remove(t)),
          ),
      ],
    );
  }

  Widget _photoRow() {
    return Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: [
        for (var i = 0; i < _photos.length; i++)
          Stack(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.memory(_photos[i].bytes, width: 80, height: 80, fit: BoxFit.cover),
              ),
              Positioned(
                top: 2,
                right: 2,
                child: GestureDetector(
                  onTap: () => setState(() => _photos.removeAt(i)),
                  child: Container(
                    padding: const EdgeInsets.all(2),
                    decoration: const BoxDecoration(color: Colors.black54, shape: BoxShape.circle),
                    child: const Icon(Icons.close, size: 14, color: Colors.white),
                  ),
                ),
              ),
            ],
          ),
        if (_photos.length < _maxPhotos)
          GestureDetector(
            key: const ValueKey('ticketAddPhoto'),
            onTap: _addPhoto,
            child: Container(
              width: 80,
              height: 80,
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.border),
              ),
              child: _uploading
                  ? const Center(
                      child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)))
                  : const Icon(Icons.add_a_photo_outlined, color: AppColors.textTertiary),
            ),
          ),
      ],
    );
  }

  Widget _submitBar(AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.md),
      child: SizedBox(
        width: double.infinity,
        child: FilledButton(
          key: const ValueKey('ticketSubmit'),
          onPressed: _canSubmit ? _submit : null,
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.mint,
            foregroundColor: Colors.white,
            disabledBackgroundColor: AppColors.mint.withValues(alpha: 0.4),
            padding: const EdgeInsets.symmetric(vertical: 14),
          ),
          child: _submitting
              ? const SizedBox(
                  width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : Text(l10n.ticketSubmit),
        ),
      ),
    );
  }
}
