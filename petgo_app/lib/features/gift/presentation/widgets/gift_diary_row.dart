import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../social/domain/pet_follow_state.dart';
import '../../data/gift_repository.dart';
import '../../domain/gift_send_result.dart';
import '../../presentation/gift_send_ceremony.dart' show kGiftBoxClosedAsset;
import '../gift_page.dart';
import '../gift_send_flow.dart';
import '../gift_style.dart';

/// Diary 礼物行（V1.5.0 gift-pack Story 2-4 · F1 主人 / A1 访客）：标题「Hadiah {petName} ›」（主人右侧「{n} hadiah」）+
/// 横滑最近 ≤10 份（右下送礼人小头像；主人未拆带「Baru」）。没有礼物只留标题。点标题 / 图标 → 礼物页。
class GiftDiaryRow extends ConsumerWidget {
  const GiftDiaryRow({super.key, required this.petId, required this.petName});

  final int petId;
  final String petName;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final preview = ref.watch(giftPreviewProvider(petId)).value;
    final owner = preview?.unopenedCount != null;
    void open() => context.push(GiftPage.routeFor(petId));
    return Container(
      key: const ValueKey('giftDiaryRow'),
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        InkWell(
          key: const ValueKey('giftDiaryRowTitle'),
          onTap: open,
          child: Row(children: [
            Expanded(
              child: Text('${l10n.giftPageTitle(petName)} ›',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
            ),
            if (owner && preview != null)
              Text(l10n.giftDetailCount(preview.giftCount),
                  key: const ValueKey('giftDiaryRowCount'),
                  style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ]),
        ),
        if (preview != null && preview.recent.isNotEmpty) ...[
          const SizedBox(height: 8),
          SizedBox(
            height: 64,
            child: ListView.separated(
              key: const ValueKey('giftDiaryRowStrip'),
              scrollDirection: Axis.horizontal,
              // 内边距：「Baru」角标与送礼人头像伸出格外不被裁
              padding: const EdgeInsets.fromLTRB(2, 6, 8, 2),
              itemCount: preview.recent.length,
              separatorBuilder: (_, _) => const SizedBox(width: 10),
              itemBuilder: (_, i) {
                final c = preview.recent[i];
                // 横向 ListView 会把子项撑满交叉轴高度：顶对齐保住 52×52，送礼人头像不被裁（2-4 复审 #3）
                return Align(
                  alignment: Alignment.topLeft,
                  child: GestureDetector(
                  key: ValueKey('giftDiaryRowItem-${c.orderToken}'),
                  onTap: open,
                  child: SizedBox(
                    width: 52,
                    height: 52,
                    child: Stack(clipBehavior: Clip.none, children: [
                      Container(
                        width: 52,
                        height: 52,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(color: GiftStyle.tint, borderRadius: BorderRadius.circular(12)),
                        child: c.unopened
                            ? Image.asset(kGiftBoxClosedAsset,
                                width: 38,
                                errorBuilder: (_, _, _) => const Icon(Icons.card_giftcard, color: AppColors.mint))
                            : giftIcon(c.giftIconUrl, size: 36),
                      ),
                      Positioned(right: -4, bottom: -4, child: giftSenderAvatar(c.sender, size: 18)),
                      if (c.unopened)
                        Positioned(
                          top: -6,
                          right: -6,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                            decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(999)),
                            child: Text(l10n.giftNewBadge,
                                style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: Colors.white)),
                          ),
                        ),
                    ]),
                  ),
                  ),
                );
              },
            ),
          ),
        ],
      ]),
    );
  }
}

/// 他人宠物信息卡内并排的「Ikuti」+「Kirim Hadiah」（V1.5.0 gift-pack Story 2-4 · A1 / A7）：各占一半、间距 8、高 40、圆角 12；
/// 一次只有一个实心主按钮——未关注：Ikuti 实心、Kirim Hadiah 浅紫；已关注：Mengikuti 白底描边、Kirim Hadiah 实心。
class GiftFollowAndSendRow extends ConsumerStatefulWidget {
  const GiftFollowAndSendRow({
    super.key,
    required this.petId,
    required this.followButton,
    required this.serverFollow,
    this.followSnapshotEpoch = 0,
    this.onDelivered,
  });

  final int petId;

  /// 现有 `PetFollowButton`（profileFull）。
  final Widget followButton;
  final bool serverFollow;
  final int followSnapshotEpoch;
  final VoidCallback? onDelivered;

  @override
  ConsumerState<GiftFollowAndSendRow> createState() => _GiftFollowAndSendRowState();
}

class _GiftFollowAndSendRowState extends ConsumerState<GiftFollowAndSendRow> {
  bool _running = false;

  Future<void> _send() async {
    if (_running) return;
    _running = true;
    try {
      await runGiftSendFlow(
        context: context,
        ref: ref,
        recipient: GiftRecipientRef.pet(widget.petId),
        source: giftSourceDiaryTab,
        onDelivered: () {
          if (!mounted) return;
          ref.invalidate(giftPreviewProvider(widget.petId));
          widget.onDelivered?.call();
        },
      );
    } finally {
      _running = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final following = resolvePetFollow(ref.watch(petFollowStateProvider)[widget.petId], widget.serverFollow,
        snapshotEpoch: widget.followSnapshotEpoch);
    final solid = following;
    return Row(children: [
      Expanded(child: SizedBox(height: 40, child: widget.followButton)),
      const SizedBox(width: 8),
      Expanded(
        child: SizedBox(
          height: 40,
          child: FilledButton.icon(
            key: const ValueKey('giftDiarySendButton'),
            onPressed: _send,
            icon: Icon(Icons.redeem, size: 16, color: solid ? Colors.white : const Color(0xFF6C48AE)),
            label: Text(l10n.giftSendButton,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: 13.5, fontWeight: FontWeight.w700, color: solid ? Colors.white : const Color(0xFF6C48AE))),
            style: FilledButton.styleFrom(
              backgroundColor: solid ? AppColors.mint : const Color(0xFFEFE6FF),
              padding: const EdgeInsets.symmetric(horizontal: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
        ),
      ),
    ]);
  }
}
