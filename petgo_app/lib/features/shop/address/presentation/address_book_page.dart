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
  const AddressBookPage({super.key});

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
  const _AddressCard({required this.address, required this.onChanged});

  final ShippingAddress address;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
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
