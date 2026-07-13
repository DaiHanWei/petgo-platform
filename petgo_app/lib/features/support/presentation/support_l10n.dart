import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/support_ticket.dart';

/// 工单枚举 → 本地化文案 + 徽章配色（列表/详情/表单共用，勿在页面内联硬编码）。

String ticketStatusLabel(AppLocalizations l10n, TicketStatus s) => switch (s) {
      TicketStatus.open => l10n.ticketStatusOpen,
      TicketStatus.inProgress => l10n.ticketStatusInProgress,
      TicketStatus.resolved => l10n.ticketStatusResolved,
      TicketStatus.closed => l10n.ticketStatusClosed,
      TicketStatus.unknown => l10n.ticketStatusOpen,
    };

/// 徽章 (背景, 文字) 配色。
({Color bg, Color fg}) ticketStatusColors(TicketStatus s) => switch (s) {
      TicketStatus.open => (bg: AppColors.goldTint, fg: AppColors.tipsBadgeText),
      TicketStatus.inProgress => (bg: AppColors.mintTint, fg: AppColors.mint600),
      TicketStatus.resolved => (bg: AppColors.momenBadgeBg, fg: AppColors.momenBadgeText),
      TicketStatus.closed => (bg: AppColors.line2, fg: AppColors.muted),
      TicketStatus.unknown => (bg: AppColors.line2, fg: AppColors.muted),
    };

String ticketLabelText(AppLocalizations l10n, TicketLabelType t) => switch (t) {
      TicketLabelType.bug => l10n.ticketLabelBug,
      TicketLabelType.feature => l10n.ticketLabelFeature,
      TicketLabelType.consultComplaint => l10n.ticketLabelConsultComplaint,
      TicketLabelType.refund => l10n.ticketLabelRefund,
      TicketLabelType.content => l10n.ticketLabelContent,
      TicketLabelType.account => l10n.ticketLabelAccount,
      TicketLabelType.praise => l10n.ticketLabelPraise,
      TicketLabelType.other => l10n.ticketLabelOther,
    };

String contactTypeLabel(AppLocalizations l10n, ContactType c) => switch (c) {
      ContactType.email => l10n.ticketContactEmail,
      ContactType.whatsapp => l10n.ticketContactWhatsapp,
    };
