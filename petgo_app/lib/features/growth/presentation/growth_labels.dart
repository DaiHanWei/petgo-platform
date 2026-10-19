import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../domain/growth_models.dart';

/// 规则 / 流水 / 权益的本地化名称与图标（Story 1.5 · AC3 / AC5）。🔴 只按代码映射，不渲染后端显示串（`note` 例外）。
class GrowthLabels {
  GrowthLabels._();

  /// 等级页规则行图标。
  static IconData ruleIcon(String code) => switch (code) {
        'DAILY_LOGIN' => Icons.login,
        'POST_PUBLISHED' => Icons.edit_note,
        'COMMENT_POSTED' => Icons.mode_comment,
        'LIKE_GIVEN' => Icons.favorite,
        'PLACE_CHECKIN' => Icons.location_on,
        'LIKE_RECEIVED' || 'COMMENT_RECEIVED' || 'COLLECT_RECEIVED' => Icons.volunteer_activism,
        'MILESTONE_S' || 'MILESTONE_M' || 'MILESTONE_L' => Icons.emoji_events,
        'GIFT_SENT' || 'GIFT_RECEIVED' => Icons.redeem,
        'SPEND_DIGITAL' => Icons.payments,
        'SPEND_SHOP' => Icons.shopping_bag,
        _ => Icons.bolt,
      };

  /// 等级页规则行名称（被动三项 / 里程碑三项由调用方合成一行，用 [GrowthLabels.passiveName] / [milestoneName]）。
  static String ruleName(AppLocalizations l10n, String code) => switch (code) {
        'DAILY_LOGIN' => l10n.growthRuleDailyLogin,
        'POST_PUBLISHED' => l10n.growthRulePost,
        'COMMENT_POSTED' => l10n.growthRuleComment,
        'LIKE_GIVEN' => l10n.growthRuleLike,
        'PLACE_CHECKIN' => l10n.growthRuleCheckin,
        'GIFT_SENT' => l10n.growthRuleGiftSent,
        'GIFT_RECEIVED' => l10n.growthRuleGiftReceived,
        'SPEND_DIGITAL' => l10n.growthRuleSpendDigital,
        'SPEND_SHOP' => l10n.growthRuleSpendShop,
        _ => l10n.growthExpGeneric,
      };

  /// 权益代码 → 文案；未知代码返回 null（UI 忽略不显示）。
  static String? benefit(AppLocalizations l10n, String code) => switch (code) {
        'LEVEL_BADGE' => l10n.growthBenefitLevelBadge,
        'PET_SLOT_PLUS_ONE' => l10n.growthBenefitPetSlot,
        'FRAME_BASIC' => l10n.growthBenefitFrameBasic,
        'FRAME_UPGRADED' => l10n.growthBenefitFrameUpgraded,
        'FRAME_ANIMATED' => l10n.growthBenefitFrameAnimated,
        'EXCLUSIVE_GIFTS_BATCH1' => l10n.growthBenefitGiftsBatch1,
        'EXCLUSIVE_GIFTS_BATCH2' => l10n.growthBenefitGiftsBatch2,
        'FEED_BOOST' => l10n.growthBenefitFeedBoost,
        _ => null,
      };

  static const _deductReasons = {'REFUND', 'UNLIKE', 'UNCOLLECT', 'TAKEDOWN'};

  /// 明细行名称（AC5 名称映射表）。
  static String ledgerTitle(AppLocalizations l10n, GrowthLedgerEntry e) {
    if (e.delta < 0 && _deductReasons.contains(e.reason)) {
      final reason = switch (e.reason) {
        'REFUND' => l10n.growthExpReasonRefund,
        'UNLIKE' => l10n.growthExpReasonUnlike,
        'UNCOLLECT' => l10n.growthExpReasonUncollect,
        _ => l10n.growthExpReasonTakedown,
      };
      return l10n.growthExpDeducted(reason);
    }
    switch (e.ruleCode) {
      case 'ADMIN_ADJUST':
        return l10n.growthExpAdminAdjust;
      case 'OPS_CAMPAIGN':
        final note = e.note?.trim();
        return note == null || note.isEmpty ? l10n.growthExpEventNoName : l10n.growthExpEvent(note);
      case 'LAUNCH_BACKFILL':
        return l10n.growthExpBackfill;
      case 'DAILY_LOGIN':
        return l10n.growthRuleDailyLogin;
      case 'POST_PUBLISHED':
        final type = switch (e.detailCode) {
          'GROWTH_MOMENT' => l10n.growthExpPostTypeDiary,
          'DAILY' => l10n.growthExpPostTypeMoment,
          'KNOWLEDGE' => l10n.growthExpPostTypeTips,
          _ => null,
        };
        return type == null ? l10n.growthExpPostPlain : l10n.growthExpPostTyped(type);
      case 'COMMENT_POSTED':
        return l10n.growthRuleComment;
      case 'LIKE_GIVEN':
        return l10n.growthRuleLike;
      case 'PLACE_CHECKIN':
        return l10n.growthRuleCheckin;
      case 'LIKE_RECEIVED':
        return l10n.growthExpLikeReceived;
      case 'COMMENT_RECEIVED':
        return l10n.growthExpCommentReceived;
      case 'COLLECT_RECEIVED':
        return l10n.growthExpCollectReceived;
      case 'MILESTONE_S':
        return l10n.growthExpMilestone('S');
      case 'MILESTONE_M':
        return l10n.growthExpMilestone('M');
      case 'MILESTONE_L':
        return l10n.growthExpMilestone('L');
      case 'GIFT_SENT':
        return l10n.growthRuleGiftSent;
      case 'GIFT_RECEIVED':
        return l10n.growthRuleGiftReceived;
      case 'SPEND_DIGITAL':
        final detail = _purpose(l10n, e.detailCode);
        return detail == null ? l10n.growthRuleSpendDigital : l10n.growthExpSpendDetail(l10n.growthRuleSpendDigital, detail);
      case 'SPEND_SHOP':
        return l10n.growthRuleSpendShop;
    }
    return l10n.growthExpGeneric;
  }

  /// 明细行图标（AC5 名称映射表）。
  static IconData ledgerIcon(GrowthLedgerEntry e) {
    if (e.delta < 0 && _deductReasons.contains(e.reason)) {
      return switch (e.reason) {
        'REFUND' => Icons.currency_exchange,
        'UNLIKE' => Icons.heart_broken,
        'UNCOLLECT' => Icons.bookmark_remove,
        _ => Icons.remove_circle,
      };
    }
    return switch (e.ruleCode) {
      'ADMIN_ADJUST' => Icons.admin_panel_settings,
      'OPS_CAMPAIGN' => Icons.celebration,
      'LAUNCH_BACKFILL' => Icons.history,
      'LIKE_RECEIVED' => Icons.favorite,
      'COMMENT_RECEIVED' => Icons.mode_comment,
      'COLLECT_RECEIVED' => Icons.bookmark,
      _ => ruleIcon(e.ruleCode),
    };
  }

  static String? _purpose(AppLocalizations l10n, String? code) => switch (code) {
        'ID_HD' => l10n.growthExpPurposeIdHd,
        'VET_CONSULT' => l10n.growthExpPurposeVetConsult,
        'AI_UNLOCK' => l10n.growthExpPurposeAiUnlock,
        'TAILSONALITY' => l10n.growthExpPurposeTailsonality,
        'PASSPORT_SNAP' => l10n.growthExpPurposePassportSnap,
        'BOARDING_PASS' => l10n.growthExpPurposeBoardingPass,
        'TS_MATCH' => l10n.growthExpPurposeTsMatch,
        'PET_SLOT_RENT' => l10n.growthExpPurposePetSlotRent,
        _ => null,
      };
}
