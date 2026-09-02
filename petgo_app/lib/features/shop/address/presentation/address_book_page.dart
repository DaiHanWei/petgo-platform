import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../data/address_repository.dart';
import '../domain/shipping_address.dart';

/// 地址簿（Story 2.4，FR-98）。
///
/// 🔴 **删除默认地址后要明确告诉用户「已将 X 设为默认」** ——
/// 服务端会自动把剩余中最近使用的一条升为默认（Story 2.1），
/// 但用户看不见这件事就会以为默认地址丢了，下单时才发现寄到了别处。
class AddressBookPage extends ConsumerWidget {
  const AddressBookPage({super.key, this.selecting = false});

  /// 选择器模式（D-18，2026-09-02 stag）。
  ///
  /// 🔴 从**结算页**进来时，这一页要回答的是「这单寄哪」，不是「管理我的地址」。
  /// 此前两者是同一个页面同一套交互：点卡片**毫无反应**，页面给的是
  /// 「设为默认 / 编辑 / 删除」——**管理操作，不是选择操作**。
  /// 于是多地址用户想把这单寄公司，唯一办法是把公司地址**设为默认**；
  /// 下单后想寄回家，还得再切一次。默认地址被当成"当前选择"用，语义错位。
  ///
  /// 选择器模式下：点卡片即选中并 pop 回 token，**只作用于当前订单，不改默认地址**。
  /// ⚠️ 「设为默认」仍然保留 —— 用户可能正想顺手改，只是它不再是换地址的**唯一**途径。
  final bool selecting;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(addressListProvider);

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(l10n.addressBookTitle), backgroundColor: AppColors.cream),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(child: Text(l10n.addressLoadFailed)),
        data: (items) => items.isEmpty
            ? Center(child: Text(l10n.addressEmpty))
            : ListView.separated(
                padding: const EdgeInsets.all(AppSpacing.lg),
                itemCount: items.length,
                separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.md),
                itemBuilder: (context, i) => _AddressCard(
                  address: items[i],
                  selecting: selecting,
                  onChanged: () => ref.invalidate(addressListProvider),
                ),
              ),
      ),
      floatingActionButton: async.maybeWhen(
        data: (items) => FloatingActionButton.extended(
          // 🔴 达上限时不给入口——比让用户填完再被服务端拒绝好得多
          onPressed: items.length >= 20 ? null : () => context.push('/me/addresses/new'),
          label: Text(items.length >= 20 ? l10n.addressLimitReached : l10n.addressAdd),
          icon: const Icon(Icons.add),
        ),
        orElse: () => null,
      ),
    );
  }
}

class _AddressCard extends ConsumerWidget {
  const _AddressCard(
      {required this.address, required this.onChanged, this.selecting = false});

  final ShippingAddress address;
  final VoidCallback onChanged;

  /// 见 [AddressBookPage.selecting]。
  final bool selecting;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final card = _card(context, ref, l10n);
    if (!selecting) return card;
    // 🔴 整卡可点：用户在选择器里想点的是**这张卡**，不是卡里的某个按钮。
    //    pop 回 token，由结算页决定怎么用（只作用于本单）。
    return Semantics(
      button: true,
      label: l10n.addressUseThis,
      child: InkWell(
        key: ValueKey('addressSelect_${address.token}'),
        borderRadius: BorderRadius.circular(AppSpacing.md),
        onTap: () => Navigator.of(context).pop(address.token),
        child: card,
      ),
    );
  }

  Widget _card(BuildContext context, WidgetRef ref, AppLocalizations l10n) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.cream,
        borderRadius: BorderRadius.circular(AppSpacing.md),
        border: Border.all(color: address.isDefault ? AppColors.mint : AppColors.mintTint),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('${address.receiverName} · ${address.receiverPhone}',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
              ),
              if (address.isDefault)
                Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.sm, vertical: AppSpacing.xxs),
                  decoration: BoxDecoration(
                    color: AppColors.mintTint,
                    borderRadius: BorderRadius.circular(AppSpacing.xs),
                  ),
                  child: Text(l10n.addressDefaultBadge,
                      style: const TextStyle(fontSize: 11, color: AppColors.mint600)),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.xs),
          Text('${address.addressLine}, ${address.kecamatan}, '
              '${address.kotaKabupaten}, ${address.provinsi} ${address.kodePos}'),
          if (address.label != null) ...[
            const SizedBox(height: AppSpacing.xs),
            Text(address.label!, style: const TextStyle(fontSize: 12, color: AppColors.mint600)),
          ],
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              if (!address.isDefault)
                TextButton(
                  onPressed: () async {
                    await ref.read(addressRepositoryProvider).setDefault(address.token);
                    onChanged();
                  },
                  child: Text(l10n.addressSetDefault),
                ),
              TextButton(
                onPressed: () => context.push('/me/addresses/${address.token}'),
                child: Text(l10n.addressEdit),
              ),
              TextButton(
                onPressed: () => _delete(context, ref),
                child: Text(l10n.addressDelete),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _delete(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final wasDefault = address.isDefault;
    final repo = ref.read(addressRepositoryProvider);

    await repo.delete(address.token);
    // 🔴 删的是默认地址 → 服务端已自动升级另一条，必须把结果告诉用户，
    //    否则他会以为默认地址没了，下单时才发现寄到别处。
    if (wasDefault) {
      final rest = await repo.list();
      final promoted = rest.where((a) => a.isDefault).firstOrNull;
      if (promoted != null) {
        messenger.showSnackBar(
            SnackBar(content: Text(l10n.addressDefaultPromoted(promoted.receiverName))));
      }
    }
    onChanged();
  }
}
