import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../content/domain/content_pet_ref.dart';
import '../../../profile/data/pet_list_repository.dart';
import '../../../profile/presentation/widgets/pet_picker_sheet.dart';
import '../../data/gift_repository.dart';
import '../../domain/gift_send_result.dart';
import '../../domain/gift_showcase.dart';
import '../gift_page.dart';
import '../gift_send_flow.dart';
import '../gift_style.dart';

/// A1：访客视图标题行右侧的紫色实心胶囊「Kirim Hadiah」（V1.4.0 gift Story 1.3 · AC1.1）。
///
/// 视觉高 34，点击热区补到 44。流程进行中再点不响应（防连点开两条流程）。
class GiftSendPill extends ConsumerStatefulWidget {
  const GiftSendPill({super.key, required this.petId, this.flightTargetKey, this.onDelivered});

  final int petId;

  /// 送出动效落点（宠物信息卡头像）。
  final GlobalKey? flightTargetKey;
  final VoidCallback? onDelivered;

  @override
  ConsumerState<GiftSendPill> createState() => _GiftSendPillState();
}

class _GiftSendPillState extends ConsumerState<GiftSendPill> {
  bool _running = false;

  Future<void> _start() async {
    if (_running) return;
    _running = true;
    try {
      await runGiftSendFlow(
        context: context,
        ref: ref,
        recipient: GiftRecipientRef.pet(widget.petId),
        source: giftSourceDiaryTab,
        flightTargetKey: widget.flightTargetKey,
        onDelivered: widget.onDelivered,
      );
    } finally {
      _running = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Semantics(
      button: true,
      child: GestureDetector(
        key: const ValueKey('giftSendPill'),
        behavior: HitTestBehavior.opaque,
        onTap: _start,
        child: SizedBox(
          height: 44,
          child: Center(
            child: Container(
              height: 34,
              padding: const EdgeInsets.fromLTRB(11, 0, 14, 0),
              decoration: BoxDecoration(
                color: AppColors.mint,
                borderRadius: BorderRadius.circular(999),
                boxShadow: const [
                  BoxShadow(color: Color(0x47845EC9), offset: Offset(0, 4), blurRadius: 12),
                ],
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.redeem, size: 16, color: Colors.white),
                  const SizedBox(width: 4),
                  Text(l10n.giftSendButton,
                      style: const TextStyle(
                          fontSize: 12.5, fontWeight: FontWeight.w700, color: Colors.white)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A3 ~ A6：Diary 详情页的礼物区（V1.4.0 gift Story 1.3 · AC1.2 建区与按钮；Story 2.2 · AC4 加宠物行）。
///
/// 浅紫底卡（#F8F6FF，圆角 16）→「HADIAH」→ 每只宠物一张白色小卡（头像 32 + 名字 +「N hadiah」+ 前 3 种礼物
/// 叠放小圆片 +「+N」+「›」，点行进该宠物礼物页；0 礼物显示 A5 副标题、仍可点）→ 卡片底部整宽次级「Kirim Hadiah」。
/// 按钮本身与显隐规则属 1.3（[showSendButton]，自己的 Diary A6 不显示）。
///
/// gift Story 4.1 · AC4（A7）：一个区只有一个「Kirim Hadiah」；`giftPets ≥ 2` → 先弹选宠物抽屉（复用 ② 的
/// [PetPickerSheet] 单选 + 吸底「Lanjut」，默认第一只，只读宠物照常可选），选定后关抽屉再开送礼面板（不叠层）；
/// 只有 1 只 → 直接开面板。两种情况一律按 `petId` 取目录与发起，帖子 id 只作来源记录。
class GiftDetailZone extends ConsumerStatefulWidget {
  const GiftDetailZone({
    super.key,
    required this.postId,
    this.pets = const [],
    this.linkedPets = const [],
    this.showSendButton = true,
    this.isOwner = false,
    this.onDelivered,
  });

  final int postId;

  /// 送达后回调（V1.4.0 L2 第二轮 P1-1）：详情页据此重拉详情，礼物区的「N hadiah」/ 叠放礼物随之刷新。
  final VoidCallback? onDelivered;

  /// 详情 `giftPets`（Story 2.1；4.1 起为全部关联宠物，与 [linkedPets] 同序）。
  final List<GiftPetSummary> pets;

  /// 详情 `pets`（② 3.3）：A7 行副标题的物种取这里（`giftPets` 不带物种）。
  final List<ContentPetRef> linkedPets;

  /// 「Kirim Hadiah」显隐（1.3 规则：他人的 Diary 且作者未注销）。
  final bool showSendButton;

  /// 自己的 Diary（A6）：0 礼物行用主人口径副标题（不劝主人「当第一个送礼的人」，V1.4.0 L2 P1-4）。
  final bool isOwner;

  @override
  ConsumerState<GiftDetailZone> createState() => _GiftDetailZoneState();
}

class _GiftDetailZoneState extends ConsumerState<GiftDetailZone> {
  /// 送出动效落点 = 礼物区本身。
  final GlobalKey _zoneKey = GlobalKey(debugLabel: 'giftDetailZone');
  bool _running = false;

  Future<void> _start() async {
    if (_running) return;
    // 详情页游客也能看：送礼接口仅 USER，游客先走登录门控（与点赞 / 评论同一入口）。
    if (!requireLogin(ref, context, entrySource: 'gift')) return;
    final pets = widget.pets;
    if (pets.isEmpty) return;
    _running = true;
    try {
      final multi = pets.length >= 2;
      var petId = pets.first.petId;
      if (multi) {
        final picked = await _pickPet(pets);
        if (picked == null || !mounted) return; // 下滑 / 返回 = 不送
        petId = picked;
      }
      if (!mounted) return;
      await runGiftSendFlow(
        context: context,
        ref: ref,
        recipient: GiftRecipientRef.pet(petId),
        source: giftSourceContentDetail,
        flightTargetKey: _zoneKey,
        sourcePostId: widget.postId,
        isMultiPetPost: multi,
        onDelivered: () {
          if (!mounted) return;
          // 礼物页若已缓存过这只宠物，回去时也要是新的。
          ref.invalidate(petGiftShowcaseProvider(petId));
          widget.onDelivered?.call();
        },
      );
    } finally {
      _running = false;
    }
  }

  /// A7：选择送给哪只（单选 + 吸底「Lanjut」，默认第一只；只读宠物不置灰）。关闭 → null。
  Future<int?> _pickPet(List<GiftPetSummary> pets) async {
    final l10n = AppLocalizations.of(context);
    final types = {for (final p in widget.linkedPets) p.petId: p.petType};
    final ids = await showPetPickerSheet(
      context,
      pets: [
        for (final p in pets)
          PetListItem(id: p.petId, name: p.name, avatarUrl: p.avatarUrl, petType: types[p.petId]),
      ],
      title: l10n.giftPickPetTitle,
      body: l10n.giftPickPetSubtitle,
      multiSelect: false,
      confirmLabel: l10n.petPickerContinue,
      initialSelectedId: pets.first.petId,
    );
    return (ids == null || ids.isEmpty) ? null : ids.first;
  }

  /// 点宠物行进礼物页（AC4.3 / AC4.4）；礼物页接口仅 USER → 游客先登录门控。
  void _openPet(int petId) {
    if (!requireLogin(ref, context, entrySource: 'gift')) return;
    context.push(GiftPage.routeFor(petId));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final lang = Localizations.localeOf(context).languageCode;
    return Container(
      key: _zoneKey,
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.mintTint2,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(6, 2, 6, 0),
            child: Text(l10n.giftZoneTitle.toUpperCase(),
              key: const ValueKey('giftZoneTitle'),
              style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.6,
                  color: AppColors.muted)),
          ),
          for (final pet in widget.pets) ...[
            const SizedBox(height: 8),
            _GiftPetRow(
                pet: pet, lang: lang, isOwner: widget.isOwner, onTap: () => _openPet(pet.petId)),
          ],
          if (widget.showSendButton) ...[
            const SizedBox(height: 8),
            SizedBox(
              height: 44,
              child: OutlinedButton.icon(
                key: const ValueKey('giftDetailSendButton'),
                onPressed: _start,
                icon: const Icon(Icons.redeem, size: 18, color: AppColors.mint),
                label: Text(l10n.giftSendButton,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
                style: OutlinedButton.styleFrom(
                  backgroundColor: Colors.white,
                  side: const BorderSide(color: AppColors.dashedViolet, width: 1.5),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// 详情礼物区的一只宠物（有礼物：白色小卡；0 礼物：稿 `.gz-r.empty` 无底无影，整行仍可点）。
class _GiftPetRow extends StatelessWidget {
  const _GiftPetRow(
      {required this.pet, required this.lang, required this.onTap, this.isOwner = false});

  final GiftPetSummary pet;
  final String lang;
  final VoidCallback onTap;
  final bool isOwner;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final empty = pet.giftCount <= 0;
    final avatar = AppImage.provider(pet.avatarUrl, thumbWidth: 120);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: empty ? Colors.transparent : Colors.white,
        borderRadius: BorderRadius.circular(14),
        boxShadow: empty
            ? null
            : const [BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8)],
      ),
      child: Material(
      type: MaterialType.transparency,
      child: InkWell(
        key: ValueKey('giftPetRow_${pet.petId}'),
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Padding(
            padding: empty
                ? const EdgeInsets.symmetric(horizontal: 6, vertical: 2)
                : const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Row(
              children: [
                // 头像 32：白 2 + 淡紫 1.5 双环（稿 `.gz-r .pet-av`）。
                Container(
                  width: 32,
                  height: 32,
                  decoration: const BoxDecoration(
                    shape: BoxShape.circle,
                    boxShadow: [
                      BoxShadow(color: AppColors.dashedViolet, spreadRadius: 3.5),
                      BoxShadow(color: Colors.white, spreadRadius: 2),
                    ],
                  ),
                  child: CircleAvatar(
                    radius: 16,
                    backgroundColor: GiftStyle.avatarBg,
                    backgroundImage: avatar,
                    child: avatar == null ? const Text('🐾', style: TextStyle(fontSize: 14)) : null,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(pet.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 13.5, fontWeight: FontWeight.w700, color: AppColors.ink)),
                      Text(
                          empty
                              ? (isOwner ? l10n.giftDetailEmptyOwner : l10n.giftDetailEmpty)
                              : l10n.giftDetailCount(pet.giftCount),
                          key: ValueKey('giftPetRowCount_${pet.petId}'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11.5, color: AppColors.ink2)),
                    ],
                  ),
                ),
                // A5（0 礼物）：无图标、无「›」，但整行仍可点进 E3 / E4 空态。
                if (!empty) ...[
                  _stack(),
                  if (pet.moreKinds > 0)
                    Padding(
                      padding: const EdgeInsets.only(left: 4),
                      child: Text('+${pet.moreKinds}',
                          key: ValueKey('giftPetRowMore_${pet.petId}'),
                          style: const TextStyle(fontSize: 11.5, color: AppColors.ink2)),
                    ),
                  const SizedBox(width: 2),
                  const Icon(Icons.chevron_right_rounded, size: 20, color: AppColors.muted),
                ],
              ],
            ),
          ),
        ),
      ),
      ),
    );
  }

  /// 前 3 种礼物叠放小圆片（与礼物墙同序）。
  Widget _stack() {
    final items = pet.wallPreview.take(3).toList();
    const d = 22.0;
    const step = 15.0;
    return SizedBox(
      key: ValueKey('giftPetRowIcons_${pet.petId}'),
      width: items.isEmpty ? 0 : d + step * (items.length - 1),
      height: d,
      child: Stack(
        children: [
          for (var i = 0; i < items.length; i++)
            Positioned(
              left: step * i,
              child: Semantics(
                label: items[i].nameFor(lang),
                child: Container(
                  width: d,
                  height: d,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: GiftStyle.tint,
                    shape: BoxShape.circle,
                    border: Border.all(color: Colors.white, width: 1.5),
                  ),
                  child: giftIcon(items[i].iconUrl, size: 14),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
