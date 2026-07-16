import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/media_permission.dart';
import '../../media/data/oss_uploader.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/consult_repository.dart';
import '../domain/consult_request.dart';
import 'vet_request_confirm_page.dart' show kVetConsultPriceIdr, formatVetConsultIdr;

/// 直连问诊病例填写页（Story F）。
///
/// 与 AI 分诊上传页同形(症状 + 最多 3 张照片 → 私密桶直传)，但**无任何 AI 提示**(不是 AI 分诊，
/// 是直接发给真人兽医的病例)。提交才发起 DIRECT 会话(携带 symptomText + 私密图 key) → 等待页。
/// 症状选填(允许空发起，保持原直连可空语义)，照片可选最多 3 张。
class ConsultCaseFormPage extends ConsumerStatefulWidget {
  const ConsultCaseFormPage({super.key});

  @override
  ConsumerState<ConsultCaseFormPage> createState() => _ConsultCaseFormPageState();
}

class _PickedPhoto {
  const _PickedPhoto(this.bytes, this.objectKey);
  final Uint8List bytes; // 本地预览
  final String objectKey; // 私密桶对象 key(已直传)
}

class _ConsultCaseFormPageState extends ConsumerState<ConsultCaseFormPage> {
  static const int _maxSymptomChars = 500;
  static const int _maxPhotos = 3;

  final TextEditingController _symptom = TextEditingController();
  final List<_PickedPhoto> _photos = [];
  bool _uploading = false;
  bool _submitting = false;

  @override
  void dispose() {
    _symptom.dispose();
    super.dispose();
  }

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
      if (bytes == null || !mounted) return; // 取消 / 权限拒(已弹引导)
      final OssUploadResult res = await useCase.uploadBytes(scope: MediaScope.private, bytes: bytes);
      if (!mounted) return;
      setState(() => _photos.add(_PickedPhoto(bytes, res.objectKey)));
    } catch (_) {
      _toast(l10n.consultStartFailed);
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

  /// 提交即入队付费问诊（D1/D2）：病例随入队落库，兽医据此判断是否接单。
  /// 兽医接单后用户才付款（本步不扣费），故本页 CTA 只显单价告知、不收款。
  Future<void> _submit() async {
    if (_submitting) return;
    setState(() => _submitting = true);
    final l10n = AppLocalizations.of(context);
    try {
      final req = await ref.read(consultRepositoryProvider).createRequest(
            symptomText: _symptom.text,
            imageObjectKeys: _photos.map((p) => p.objectKey).toList(),
          );
      if (!mounted) return;
      // 占用命中（alreadyActive）已在支付态 → 直跳支付屏；否则入队等待屏。
      // 替换本页 → 返回不回到已提交的病例页。
      final path = req.state == ConsultRequestState.acceptedAwaitPay
          ? '/consult/vet-request/pay/${req.requestToken}'
          : '/consult/vet-request/waiting/${req.requestToken}';
      context.pushReplacement(path);
    } on DioException catch (e) {
      // 无宠物档案 → 409（引导先建档）；其余 → 通用失败（不显后端 detail 原文）。
      _toast(e.response?.statusCode == 409 ? l10n.vetRequestNoPet : l10n.consultStartFailed);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        title: Text(l10n.consultCaseTitle),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(AppSpacing.md),
                children: [
                  Text(l10n.consultCaseDesc,
                      style: AppTypography.caption.copyWith(color: AppColors.textSecondary, height: 1.5)),
                  const SizedBox(height: AppSpacing.lg),
                  Text(l10n.consultCaseSymptomLabel,
                      style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
                  const SizedBox(height: AppSpacing.sm),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppColors.border),
                    ),
                    child: TextField(
                      key: const ValueKey('consultCaseSymptom'),
                      controller: _symptom,
                      maxLines: 5,
                      maxLength: _maxSymptomChars,
                      onChanged: (_) => setState(() {}),
                      decoration: InputDecoration(
                        hintText: l10n.triageSymptomHint,
                        hintStyle: const TextStyle(color: AppColors.muted, fontSize: 13, height: 1.6),
                        border: InputBorder.none,
                        counterText: '',
                      ),
                    ),
                  ),
                  Align(
                    alignment: Alignment.centerRight,
                    child: Text('${_symptom.text.length} / $_maxSymptomChars',
                        style: AppTypography.micro.copyWith(color: AppColors.textTertiary)),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  Text(l10n.consultCasePhotosLabel,
                      style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
                  const SizedBox(height: AppSpacing.sm),
                  _photoRow(),
                ],
              ),
            ),
            _submitBar(l10n),
          ],
        ),
      ),
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
            key: const ValueKey('consultCaseAddPhoto'),
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
                      child: SizedBox(
                          width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)))
                  : const Icon(Icons.add_a_photo_outlined, color: AppColors.textTertiary),
            ),
          ),
      ],
    );
  }

  Widget _submitBar(AppLocalizations l10n) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.md),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 价格告知（D1 方案）：接单后才付款，本步不扣费——文案须说清，避免误以为提交即扣款。
          Padding(
            padding: const EdgeInsets.only(bottom: AppSpacing.sm),
            child: Text(
              l10n.vetRequestPayAfterAccept(formatVetConsultIdr(kVetConsultPriceIdr)),
              key: const ValueKey('consultCasePriceHint'),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
            ),
          ),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('consultCaseSubmit'),
              onPressed: _submitting || _uploading ? null : _submit,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              child: _submitting
                  ? const SizedBox(
                      width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : Text(l10n.consultCaseSubmit),
            ),
          ),
        ],
      ),
    );
  }
}
