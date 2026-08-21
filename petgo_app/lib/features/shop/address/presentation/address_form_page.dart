import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../l10n/app_localizations.dart';
import '../data/address_repository.dart';
import '../domain/shipping_address.dart';

/// 新增/编辑收货地址（Story 2.4，FR-98 / C-15）。
///
/// 🔴 **三级级联**：上级未选时下级禁用 —— 允许先选 Kecamatan 再选省会让用户选出
/// 自相矛盾的组合，而运费按 Kecamatan 算、承运商按省市派送，矛盾组合会直接导致派送失败。
///
/// 🔴 **手机号校验口径与服务端完全一致**（`normalizeIdPhone` 逐条对齐 `IndonesiaPhone`）。
/// 两边不一致时用户在 App 里通过了、提交却被服务端拒，表面看是"保存失败"，
/// 实际是两套规则打架——这是最难排查的一类问题。
///
/// 🔴 **邮编与 Kecamatan 不匹配只警告不阻断**（C-15）：运费按 Kecamatan 计、
/// 承运商按邮编派送，不符值得提醒但不该挡住下单。
/// ⚠️ 当前**没有 Kecamatan→邮编 对照数据**（见 Story 2.1 登记的依赖），故该警告暂不触发；
/// 机制位置已留在 `_postcodeWarning`，拿到对照表后填进去即可。
class AddressFormPage extends ConsumerStatefulWidget {
  const AddressFormPage({super.key, this.token});

  /// null = 新增；非 null = 编辑。
  final String? token;

  @override
  ConsumerState<AddressFormPage> createState() => _AddressFormPageState();
}

class _AddressFormPageState extends ConsumerState<AddressFormPage> {
  final _receiver = TextEditingController();
  final _phone = TextEditingController();
  final _line = TextEditingController();
  final _kodePos = TextEditingController();
  final _customLabel = TextEditingController();

  String? _provinsi;
  String? _kota;
  String? _kecamatan;
  String? _label;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_receiver, _phone, _line, _kodePos, _customLabel]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final regions = ref.watch(regionTreeProvider);

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        title: Text(widget.token == null ? l10n.addressAdd : l10n.addressEdit),
        backgroundColor: AppColors.cream,
      ),
      body: regions.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(child: Text(l10n.addressLoadFailed)),
        data: (tree) => _form(l10n, tree),
      ),
    );
  }

  Widget _form(AppLocalizations l10n, RegionTree tree) {
    final kotas = tree.provinsi
            .where((p) => p.name == _provinsi)
            .expand((p) => p.kota)
            .toList();
    final kecs = kotas.where((k) => k.name == _kota).expand((k) => k.kecamatan).toList();

    return ListView(
      padding: const EdgeInsets.all(AppSpacing.lg),
      children: [
        if (_error != null) ...[
          Text(_error!, style: const TextStyle(color: Colors.red)),
          const SizedBox(height: AppSpacing.md),
        ],
        TextField(
          controller: _receiver,
          maxLength: 40,
          decoration: InputDecoration(labelText: l10n.addressReceiverName),
        ),
        TextField(
          controller: _phone,
          keyboardType: TextInputType.phone,
          decoration: InputDecoration(
            labelText: l10n.addressReceiverPhone,
            // +62 前缀展示；用户可直接输 08xxx，归一化时自动剥 0
            prefixText: '+62 ',
            helperText: l10n.addressPhoneHelper,
          ),
        ),
        const SizedBox(height: AppSpacing.md),

        // ---------- 🔴 三级级联：上级未选，下级禁用 ----------
        DropdownButtonFormField<String>(
          initialValue: _provinsi,
          decoration: InputDecoration(labelText: l10n.addressProvinsi),
          items: [
            for (final p in tree.provinsi)
              DropdownMenuItem(value: p.name, child: Text(p.name)),
          ],
          onChanged: (v) => setState(() {
            _provinsi = v;
            _kota = null;        // 上级变了，下级必须清空——留着旧值会拼出自相矛盾的地址
            _kecamatan = null;
          }),
        ),
        DropdownButtonFormField<String>(
          initialValue: _kota,
          decoration: InputDecoration(labelText: l10n.addressKota),
          items: [for (final k in kotas) DropdownMenuItem(value: k.name, child: Text(k.name))],
          onChanged: _provinsi == null
              ? null      // 🔴 上级未选 → 禁用
              : (v) => setState(() {
                    _kota = v;
                    _kecamatan = null;
                  }),
        ),
        DropdownButtonFormField<String>(
          initialValue: _kecamatan,
          decoration: InputDecoration(labelText: l10n.addressKecamatan),
          items: [
            for (final k in kecs)
              DropdownMenuItem(
                value: k.name,
                // 不可送达的区域仍可选（FR-99 允许存超范围地址），但标出来
                child: Text(k.serviceable ? k.name : '${k.name} · ${l10n.addressNotServiceable}'),
              ),
          ],
          onChanged: _kota == null ? null : (v) => setState(() => _kecamatan = v),
        ),
        const SizedBox(height: AppSpacing.md),

        TextField(
          controller: _line,
          maxLength: 120,
          maxLines: 2,
          decoration: InputDecoration(labelText: l10n.addressLine),
        ),
        TextField(
          controller: _kodePos,
          keyboardType: TextInputType.number,
          maxLength: 5,
          decoration: InputDecoration(labelText: l10n.addressKodePos),
          onChanged: (_) => setState(() {}),
        ),
        // 🔴 邮编与 Kecamatan 不匹配【只警告不阻断】（C-15）：
        //    运费按 Kecamatan 计、承运商按邮编派送，不符值得提醒但不该挡住下单。
        if (_postcodeWarning() != null)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.xs),
            child: Text(_postcodeWarning()!,
                style: const TextStyle(fontSize: 12, color: AppColors.mint600)),
          ),
        const SizedBox(height: AppSpacing.md),

        Text(l10n.addressLabel, style: const TextStyle(fontWeight: FontWeight.w600)),
        Wrap(
          spacing: AppSpacing.sm,
          children: [
            for (final preset in ['Rumah', 'Kantor'])
              ChoiceChip(
                label: Text(preset),
                selected: _label == preset,
                onSelected: (on) => setState(() => _label = on ? preset : null),
              ),
          ],
        ),
        TextField(
          controller: _customLabel,
          maxLength: 10,
          decoration: InputDecoration(labelText: l10n.addressLabelCustom),
          onChanged: (v) => setState(() => _label = v.isEmpty ? null : v),
        ),
        const SizedBox(height: AppSpacing.xl),

        FilledButton(
          onPressed: _saving ? null : _save,
          child: Text(l10n.addressSave),
        ),
      ],
    );
  }

  /// ⏳ 邮编 ↔ Kecamatan 一致性警告的机制位置。
  /// 当前返回 null —— **没有对照数据**（Story 2.1 已登记该依赖）。
  /// 🔴 硬编几个样例等于假装做了，反而会给出错误警告。
  String? _postcodeWarning() => null;

  Future<void> _save() async {
    final l10n = AppLocalizations.of(context);
    setState(() {
      _error = null;
      _saving = true;
    });
    try {
      // 🔴 与服务端同一套口径
      final phone = normalizeIdPhone(_phone.text);
      if (phone == null) {
        setState(() {
          _error = l10n.addressPhoneInvalid;
          _saving = false;
        });
        return;
      }
      if (_provinsi == null || _kota == null || _kecamatan == null) {
        setState(() {
          _error = l10n.addressRegionRequired;
          _saving = false;
        });
        return;
      }
      final a = ShippingAddress(
        token: widget.token ?? '',
        receiverName: _receiver.text.trim(),
        receiverPhone: phone,
        provinsi: _provinsi!,
        kotaKabupaten: _kota!,
        kecamatan: _kecamatan!,
        addressLine: _line.text.trim(),
        kodePos: _kodePos.text.trim(),
        label: _label,
        isDefault: false,
      );
      final repo = ref.read(addressRepositoryProvider);
      if (widget.token == null) {
        await repo.create(a);
      } else {
        await repo.update(widget.token!, a);
      }
      ref.invalidate(addressListProvider);
      if (mounted) context.pop();
    } catch (e) {
      // 🔒 错误提示不回显用户输入（可能含 PII）
      setState(() {
        _error = l10n.addressSaveFailed;
        _saving = false;
      });
    }
  }
}
