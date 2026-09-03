/// 退货申请 —— **设计稿版式**（V1.4.0 · `02_screens_orders_refund.md` 屏 4）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 四条不能省的告知（每一条对应一类真实客诉，与 v1 逐条相同）
///
/// 1. **「开封不退」的行保留可见但置灰**并标注原因 —— 比提交后再驳回体验好得多。
///    这里是该规则**三处明示的第 3 处**（第 1 处商品详情页、第 2 处结算页）。
/// 2. **每个原因选项直接标出回程运费由谁承担** —— 不等提交后才告知。
/// 3. **去程运费提示行随勾选实时切换**（UX-DR2）：全退 → 会退回；部分退 → 不退回。
///    这是堵住「免运门槛凑单 → 退掉凑单商品」套利的**唯一告知点**。
/// 4. **可退判定以服务端为准**：用服务端下发的 `selectable` / `blockedCode` 渲染（文案在端上）。
///
/// ## ⚠️ 与代码库既有约定的两处冲突（按设计稿实现，在此标注）
///
/// **① 照片张数**：设计稿要求 **min 2 / max 5**；v1 实现是「质量问题必填 ≥1，最多 6」。
/// 本页按设计稿收紧。两者都比服务端校验更严，因此不会破坏契约，
/// 但**收紧下限会挡住只拍了一张照片的用户** —— 这是产品取舍，不是实现细节，需产品确认。
///
/// **② 原因四选项 vs 两个 API 值**：设计稿列了 4 个原因，而 `ReturnType` 只有
/// `QUALITY_ISSUE` / `NON_QUALITY` 两值。此处按 4 项渲染（用户视角更准），
/// 提交时映射回两值，并把所选原因的文案写进 `reasonNote` 供质检看 ——
/// **不新增枚举值**（那要改后端 + Flyway）。
library;

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/media/media_scope.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/shop_return_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_surface.dart';

/// 设计稿的 4 个退货原因 → 后端两个 `ReturnType`。
enum ReturnReasonOption {
  wrongVariant(ReturnType.qualityIssue),
  damaged(ReturnType.qualityIssue),
  nearExpiry(ReturnType.qualityIssue),
  changedMind(ReturnType.nonQualityIssue);

  const ReturnReasonOption(this.type);

  final ReturnType type;

  /// 🔴 回程运费由谁承担 —— 直接取自 [ReturnType]，**不在 UI 层另算一遍**。
  bool get platformPaysReturnShipping => type.platformPaysReturnShipping;
}

class ReturnRequestPageV2 extends ConsumerStatefulWidget {
  const ReturnRequestPageV2({super.key, required this.orderToken});

  final String orderToken;

  @override
  ConsumerState<ReturnRequestPageV2> createState() => _ReturnRequestPageV2State();
}

class _ReturnRequestPageV2State extends ConsumerState<ReturnRequestPageV2> {
  /// 🔴 **不预选原因**。预选会让人在没看清运费归属的情况下提交 ——
  /// 而「改主意」那一档的回程运费是买家自付。
  ReturnReasonOption? _reason;

  final Map<int, int> _selected = {};

  /// 已上传的凭证照片。
  ///
  /// 🔴 **D-10（2026-09-02 stag，P0）之前这里是一串假 key**：点「+」只往列表里
  /// 追加字面量 `return-evidence-1/2/…`，**不调相册、不拍照、不上传**，
  /// 计数照跳、缩略图是占位斜纹，随后这些假串被原样提交入库。
  /// 后端也不校验 key 是否指向真实对象 ⇒ 运营在退货审核页无图可看，
  /// 而本页文案还写着「拍到封口和保质期标签 —— 这是质检要看的」。
  /// 整条凭证链路端到端不可用，「开封判例」这类依赖凭证的功能一并失去输入。
  final List<_Evidence> _evidence = [];

  /// 正在上传（选图→压缩→直传的整段）。期间「+」置灰并转圈 ——
  /// 不给态就会重演 D-8 那种「点了没反应」。
  bool _uploadingPhoto = false;
  bool _busy = false;

  /// 设计稿：min 2 / max 5（见文件头冲突说明）。
  static const int kMinPhotos = 2;
  static const int kMaxPhotos = 5;

  ReturnType get _type => _reason?.type ?? ReturnType.qualityIssue;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_return_request_viewed');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(returnEligibilityProvider(widget.orderToken));

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.returnRequestTitle),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.returnRequestLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(returnEligibilityProvider(widget.orderToken)),
        ),
        data: (data) => data.eligible ? _content(l10n, data) : _blocked(l10n, data),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (data) => data.eligible ? _bottomBar(l10n, data) : null,
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ReturnEligibility data) => ListView(
        padding: EdgeInsets.zero,
        children: [
          _itemsBlock(l10n, data),
          _reasonBlock(l10n),
          _photoBlock(l10n),
          _noReturnBlock(l10n),
          const SizedBox(height: kShopGutter),
        ],
      );

  // ---------------------------------------------------------------- 商品

  Widget _itemsBlock(AppLocalizations l10n, ReturnEligibility data) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.returnRequestItems, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
            const SizedBox(height: 10),
            for (final line in data.lines) _lineRow(l10n, line),
          ],
        ),
      );

  Widget _lineRow(AppLocalizations l10n, ReturnableLine line) {
    // 🔴 可退判定以服务端为准；切换原因时按同一份规则做本地预判
    //    （质量问题下「开封不退」的行仍可选 —— 破损/临期/错发与是否开封无关）。
    final selectable = line.selectableFor(_type);
    final picked = _selected[line.orderLineId] ?? 0;

    return Opacity(
      // 🔴 不可退的行**保留可见但置灰**并标注原因 —— 直接隐藏会让用户
      //    以为自己记错了买过什么；提交后再驳回则更差。
      opacity: selectable ? 1 : .5,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ShopCheckbox(
              key: ValueKey('returnLine_${line.orderLineId}'),
              value: picked > 0,
              enabled: selectable,
              onChanged: (v) => setState(() {
                if (v) {
                  _selected[line.orderLineId] = line.returnableQty;
                } else {
                  _selected.remove(line.orderLineId);
                }
              }),
            ),
            const SizedBox(width: 2),
            ShopImage(url: line.mainImageUrl, size: 46, radius: ShopShape.radiusChip),
            const SizedBox(width: 9),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${line.productName} · ${line.specName}',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: ShopText.productNameCard),
                  const SizedBox(height: 3),
                  Text(formatIdr(line.unitPrice),
                      style: ShopText.priceInline.copyWith(
                          color: selectable ? ShopColors.accent : ShopColors.text4)),
                  // 🔴 不可退的原因由**服务端**给，前端直接展示 ——
                  //    前端自己拼会和服务端判定漂移。
                  if (!selectable && line.blockedCode != null) ...[
                    const SizedBox(height: 3),
                    Text(_blockedText(l10n, line.blockedCode!),
                        key: ValueKey('returnBlocked_${line.orderLineId}'),
                        style: ShopText.meta.copyWith(color: ShopColors.warnTitle)),
                  ],
                ],
              ),
            ),
            if (selectable && picked > 0) ...[
              const SizedBox(width: 8),
              ShopStepper(
                value: picked,
                min: 1,
                // 数量 ≥1 且 ≤ 原购可退数（设计稿）。
                max: line.returnableQty,
                onChanged: (v) => setState(() => _selected[line.orderLineId] = v),
              ),
            ],
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- 原因

  Widget _reasonBlock(AppLocalizations l10n) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.returnRequestReason, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
            const SizedBox(height: 10),
            for (final o in ReturnReasonOption.values) ...[
              ShopRadioTile(
                key: ValueKey('returnReason_${o.name}'),
                label: _reasonLabel(l10n, o),
                selected: _reason == o,
                onTap: () => setState(() {
                  _reason = o;
                  // 换了原因可能改变各行的可退性 —— 清掉已选，避免留下一个
                  // 在新原因下不可退的勾选（服务端会拒，用户看不懂为什么）。
                  _selected.removeWhere((id, _) => true);
                }),
              ),
              const SizedBox(height: 7),
            ],
          ],
        ),
      );

  String _reasonLabel(AppLocalizations l10n, ReturnReasonOption o) => switch (o) {
        ReturnReasonOption.wrongVariant => l10n.returnReasonWrongVariant,
        ReturnReasonOption.damaged => l10n.returnReasonDamaged,
        ReturnReasonOption.nearExpiry => l10n.returnReasonNearExpiry,
        ReturnReasonOption.changedMind => l10n.returnReasonChangedMind,
      };

  // ---------------------------------------------------------------- 照片

  Widget _photoBlock(AppLocalizations l10n) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(l10n.returnRequestEvidence,
                      style: ShopText.sectionTitle.copyWith(fontSize: 12)),
                ),
                Text(l10n.returnPhotoRequired,
                    style: ShopText.meta.copyWith(fontSize: 10)),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var i = 0; i < _evidence.length; i++)
                  SizedBox(
                    width: 72,
                    height: 72,
                    child: Stack(
                      children: [
                        // 🔴 用**刚选的那份字节**渲染，不去取远端：凭证进的是**私有桶**
                        //    （与 IM 图、病例图同一口径），远端要签名 URL 才看得到，
                        //    而用户此刻只是想确认「我传的是这张」。
                        ClipRRect(
                          borderRadius: BorderRadius.circular(ShopShape.radiusField),
                          child: Image.memory(_evidence[i].bytes,
                              width: 72, height: 72, fit: BoxFit.cover),
                        ),
                        Positioned(
                          right: 2,
                          top: 2,
                          child: ShopPressable(
                            key: ValueKey('returnEvidenceRemove_$i'),
                            onTap: () => setState(() => _evidence.removeAt(i)),
                            // 18px 的 × 靠 ShopPressable 撑到 44 命中区，视觉不变。
                            minSize: kShopMinTapTarget,
                            child: Container(
                              width: 18,
                              height: 18,
                              alignment: Alignment.center,
                              decoration: const BoxDecoration(
                                  color: ShopColors.imageButtonScrim, shape: BoxShape.circle),
                              child: const Icon(Icons.close,
                                  size: 11, color: ShopColors.surface),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                if (_evidence.length < kMaxPhotos)
                  ShopPressable(
                    key: const ValueKey('returnEvidenceAddV2'),
                    // 上传中不接第二次点击：并发上传会让计数与实际入库的 key 对不上。
                    onTap: _uploadingPhoto ? null : () => _addEvidence(l10n),
                    child: CustomPaint(
                      painter: DashedRRectPainter(
                          color: ShopColors.border, radius: ShopShape.radiusField),
                      child: SizedBox(
                        width: 72,
                        height: 72,
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            if (_uploadingPhoto)
                              const SizedBox(
                                key: ValueKey('returnEvidenceUploadingV2'),
                                width: 15,
                                height: 15,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            else
                              const Icon(Icons.add, size: 15, color: ShopColors.purple),
                            const SizedBox(height: 2),
                            Text('${_evidence.length}/$kMaxPhotos',
                                style: ShopText.badge.copyWith(color: ShopColors.text3)),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 7),
            Text(l10n.returnPhotoHint, style: ShopText.meta),
          ],
        ),
      );

  /// 不可退原因码 → 本地化文案（D-9）。
  ///
  /// 🔴 兜底到最保守的那句：后端将来新增原因码时，老版本 App 拿到未知码
  /// 也得说得出「为什么不能选」—— 显示空白等于让用户以为是页面坏了。
  String _blockedText(AppLocalizations l10n, String code) => switch (code) {
        'ALL_RETURNED' => l10n.returnBlockedAllReturned,
        'NO_RETURN_AFTER_OPEN' => l10n.returnBlockedAfterOpen,
        _ => l10n.returnBlockedNonReturnable,
      };

  /// 选一张凭证并上传（D-10）。
  ///
  /// 复用全仓统一的「选图 → 权限 → 压缩剥 EXIF → 直传」用例
  /// （[MediaUploadUseCase]，工单附件页同一套路），不另起一条上传链路。
  ///
  /// 🔴 进**私有桶**：凭证是用户拍的实物照，可能带家里、面单、地址等信息，
  /// 与 IM 图 / 病例图同一口径。⚠️ 这意味着后台要渲染它得走签名 URL，
  /// 不能像商品图那样直接拼公开 URL（见 D-13）。
  Future<void> _addEvidence(AppLocalizations l10n) async {
    if (_evidence.length >= kMaxPhotos || _uploadingPhoto) return;
    final source = await _pickSourceSheet(l10n);
    if (source == null || !mounted) return;
    setState(() => _uploadingPhoto = true);
    try {
      final useCase = ref.read(mediaUploadUseCaseProvider);
      // 分两步而不是 pickAndUploadOne：要留住处理后的字节做缩略图，
      // 私有桶的对象拿不到可直接 <img> 的地址。
      final bytes = await useCase.pickAndProcess(source: source, context: context);
      if (bytes == null || !mounted) return; // 用户取消 / 权限被拒（用例内已引导）
      final res = await useCase.uploadBytes(scope: MediaScope.private, bytes: bytes);
      if (!mounted) return;
      setState(() => _evidence.add(_Evidence(bytes, res.objectKey)));
    } catch (_) {
      // 🔴 必须出声。D-8 的教训：上传失败没有任何反馈时，用户会以为是自己没点对，
      //    而这里更糟 —— 计数不涨，他会反复点。
      if (mounted) showAppToast(context, l10n.returnPhotoUploadFailed);
    } finally {
      if (mounted) setState(() => _uploadingPhoto = false);
    }
  }

  Future<MediaSource?> _pickSourceSheet(AppLocalizations l10n) =>
      showModalBottomSheet<MediaSource>(
        context: context,
        builder: (ctx) => SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                key: const ValueKey('returnEvidenceCameraV2'),
                leading: const Icon(Icons.photo_camera_outlined),
                title: Text(l10n.triagePhotoFromCamera),
                onTap: () => Navigator.pop(ctx, MediaSource.camera),
              ),
              ListTile(
                key: const ValueKey('returnEvidenceGalleryV2'),
                leading: const Icon(Icons.photo_library_outlined),
                title: Text(l10n.triagePhotoFromGallery),
                onTap: () => Navigator.pop(ctx, MediaSource.gallery),
              ),
            ],
          ),
        ),
      );

  /// 开封不退明示（三处的**第 3 处**）+ 追加拒退后果。
  ///
  /// 🔴 与商品详情页、结算页**同一批 ARB key**，逐字一致（文案一致性契约）。
  Widget _noReturnBlock(AppLocalizations l10n) => ShopSection(
        child: ShopWarnBlock(
          key: const ValueKey('returnNoReturnBlockV2'),
          title: l10n.tokoNoReturnAfterOpenTitle,
          body: '${l10n.tokoNoReturnAfterOpenBody} ${l10n.returnNoReturnAfterOpenNotice}',
        ),
      );

  // ---------------------------------------------------------------- 底部条

  Widget _bottomBar(AppLocalizations l10n, ReturnEligibility data) {
    final full = _isFullReturn(data);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // 🔴 运费归属说明**随原因实时切换**（设计稿）：平台责任 → 平台承担；
        //    改主意 → 买家承担。等提交后才告知是最典型的客诉来源。
        Container(
          width: double.infinity,
          color: ShopColors.surface,
          padding: const EdgeInsets.fromLTRB(kShopScreenEdge, 8, kShopScreenEdge, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_reason != null)
                Text(
                  _reason!.platformPaysReturnShipping
                      ? l10n.returnShipBearerPlatform
                      : l10n.returnShipBearerBuyer,
                  key: const ValueKey('returnShipBearerNoticeV2'),
                  style: ShopText.meta,
                ),
              // 🔴 去程运费提示随勾选切换 —— 堵凑单套利的唯一告知点（UX-DR2）。
              Text(
                full ? l10n.returnOutboundWillRefund : l10n.returnOutboundWillNotRefund,
                key: const ValueKey('returnOutboundFeeNoticeV2'),
                style: ShopText.meta.copyWith(
                    color: full ? ShopColors.purple : ShopColors.text3),
              ),
            ],
          ),
        ),
        ShopBottomBarActions(
          primary: ShopButton(
            key: const ValueKey('returnSubmitV2'),
            label: l10n.returnNextChooseRefund,
            variant: ShopButtonVariant.pay,
            loading: _busy,
            onTap: _busy ? null : () => _submit(l10n),
          ),
        ),
      ],
    );
  }

  bool _isFullReturn(ReturnEligibility data) {
    if (_selected.isEmpty) return false;
    for (final line in data.lines) {
      final picked = _selected[line.orderLineId] ?? 0;
      if (picked < line.returnableQty) return false;
    }
    return true;
  }

  Widget _blocked(AppLocalizations l10n, ReturnEligibility data) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(
            data.activeRequestToken != null
                ? l10n.returnBlockedActive
                : (data.ineligibleReason ?? l10n.returnRequestLoadFailed),
            textAlign: TextAlign.center,
            style: ShopText.body,
          ),
        ),
      );

  // ---------------------------------------------------------------- 提交

  Future<void> _submit(AppLocalizations l10n) async {
    if (_selected.isEmpty) {
      showAppToast(context, l10n.returnSelectAtLeastOne);
      return;
    }
    if (_reason == null) {
      showAppToast(context, l10n.returnRequestReason);
      return;
    }
    // 🔴 设计稿：照片 min 2。质量问题那一档恰恰是平台承担运费 + 发补偿溢价的，
    //    没有凭证既无法质检也无法复盘。
    // ⚠️ 「服务端也会再判一次」**2026-09-02 起才真正成立**（产品拍板：前端 2 张、后端 2 张）。
    //    此前服务端只在 QUALITY_ISSUE 时要求「非空」—— 1 张也过、换个调用方 0 张也过，
    //    等于只有这里在挡。现在两端同为 2 张（服务端 ReturnRequestService.MIN_EVIDENCE）。
    if (_evidence.length < kMinPhotos) {
      showAppToast(context, l10n.returnPhotoTooFew);
      return;
    }

    Analytics.capture('toko_return_request_submitted');
    setState(() => _busy = true);
    try {
      final progress = await ref.read(shopReturnRepositoryProvider).submit(
            orderToken: widget.orderToken,
            returnType: _type,
            selections: Map.of(_selected),
            // 4 个原因映射回 2 个 API 值后，把具体原因写进备注供质检看
            // （见文件头冲突②：不新增枚举值）。
            reasonNote: _reasonLabel(l10n, _reason!),
            evidenceKeys: [for (final e in _evidence) e.objectKey],
          );
      if (!mounted) return;
      ref.invalidate(returnEligibilityProvider(widget.orderToken));
      showAppToast(context, l10n.returnRequestSubmitted);
      // 🔴 下一步**不是提交完成**：退款去向由支付构成决定且不可选，
      //    必须先让用户看到拆分结果再确认。
      context.push('/shop/returns/${progress.returnToken}/refund-method');
    } catch (_) {
      if (mounted) showAppToast(context, l10n.returnRequestFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// 一张已上传的凭证：本地字节（画缩略图）+ 服务端 objectKey（提交用）。
///
/// ⚠️ 两者都要留：字节不能当 key 提交，key 也换不出可直接渲染的地址（私有桶）。
class _Evidence {
  const _Evidence(this.bytes, this.objectKey);

  final Uint8List bytes;
  final String objectKey;
}
