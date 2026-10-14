import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/pay_channel_picker.dart';
import '../../data/id_card_repository.dart';
import 'id_card_ui_style.dart';

/// HD 付费方式选择底部抽屉（Story 6.3；6-7 多卡详情页复用）。返回选中的 [HdPayChannel]，下滑关闭 → null。
/// PawCoin 余额足则默认选；不足 → 显充值链接跳 /me/pawcoin/recharge。
///
/// V1.3.2 Story 3.2：渠道选择主体抽到 [PayChannelPicker]，本类只保留 KTP 头部卡与文案（视觉 / key 不变）。
class HdPaywallSheet extends ConsumerWidget {
  const HdPaywallSheet(
      {super.key, this.petName, this.serialId, this.cardNo, this.avatarUrl, required this.balance});

  final String? petName;
  final int? serialId;

  /// 新编码身份码（TT 开头 14 位）。非空时展示优先于 [serialId]；旧卡为 null 走旧展示。
  final String? cardNo;
  final String? avatarUrl;
  final int balance;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final no = (cardNo?.isNotEmpty == true)
        ? cardNo!
        : (serialId == null ? '----' : serialId!.toString().padLeft(4, '0'));
    // 稿 E1–E5 主按钮 `.btn.fill`（胶囊 48 高）：PayChannelPicker 是全 App 共享件，不改它，
    // 只在本抽屉内用局部 Theme 覆盖 FilledButton 默认形状 / 高度（其显式 styleFrom 属性仍优先）。
    return Theme(
      data: Theme.of(context).copyWith(
          filledButtonTheme: FilledButtonThemeData(style: idCardPrimaryButtonStyle())),
      child: PayChannelPicker(
        // 展示价后端实时下发（417 同类修复）；无本地兜底——失败显示重试并禁付款。
        price: ref.watch(idCardHdPriceProvider),
        onRetryPrice: () => ref.invalidate(idCardHdPriceProvider),
        balance: balance,
        title: l10n.idCardHdPaywallTitle,
        body: l10n.idCardHdPaywallBody,
        confirmLabel: (_) => l10n.idCardHdPayConfirm,
        header: Container(
          padding: const EdgeInsets.symmetric(vertical: 22, horizontal: 16),
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [AppColors.mint500, AppColors.mint],
            ),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            children: [
              const Text('🐾', style: TextStyle(fontSize: 30)),
              const SizedBox(height: 8),
              Text('${petName ?? 'Pet'} · No. $no',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              Text(l10n.idCardHdPreviewSub,
                  style: TextStyle(color: Colors.white.withValues(alpha: 0.8), fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}
