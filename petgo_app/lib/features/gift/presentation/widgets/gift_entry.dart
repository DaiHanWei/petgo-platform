import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../domain/gift_send_result.dart';
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

/// A3：他人 Diary 详情页的礼物区（V1.4.0 gift Story 1.3 · AC1.2）。
///
/// 本 story 只画区标题 + 次级按钮；宠物行（头像 + n hadiah + 礼物缩略）属 Story 2.2。
/// 本分支单宠过渡：按 `postId` 由后端取收礼宠物，不弹选宠物抽屉（A7 在对齐 story 4.1 接入）。
class GiftDetailZone extends ConsumerStatefulWidget {
  const GiftDetailZone({super.key, required this.postId});

  final int postId;

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
    _running = true;
    try {
      await runGiftSendFlow(
        context: context,
        ref: ref,
        recipient: GiftRecipientRef.post(widget.postId),
        source: giftSourceContentDetail,
        flightTargetKey: _zoneKey,
      );
    } finally {
      _running = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: _zoneKey,
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: GiftStyle.tint,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(l10n.giftZoneTitle.toUpperCase(),
              key: const ValueKey('giftZoneTitle'),
              style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.6,
                  color: AppColors.muted)),
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
      ),
    );
  }
}
