/// 电商组件预览页（**仅开发期**，V1.4.0 第 1 批）。
///
/// 用法：`flutter run -t lib/dev/shop_gallery_main.dart -d <device>`
///
/// 🔴 <b>独立入口，刻意不挂进 `app_router.dart`</b>：并行开发契约要求路由表「只追加不重排」，
/// 而一个纯预览页不值得占用一条产品路由，也不该被打进 release 包。
/// 它把 `shop_*` 组件按设计稿的用法各摆一遍，用途是**在铺到 7 屏之前先验视觉语言** ——
/// 逐屏改完才发现 token 不对，返工面是 7 倍。
///
/// ⚠️ 这不是设计稿的还原页。屏与屏的**布局**在各自页面里实现，这里只验组件本身。
library;

import 'package:flutter/material.dart';

import '../core/theme/shop_tokens.dart';
import '../features/shop/presentation/widgets/shop_buttons.dart';
import '../features/shop/presentation/widgets/shop_controls.dart';
import '../features/shop/presentation/widgets/shop_countdown.dart';
import '../features/shop/presentation/widgets/shop_decor.dart';
import '../features/shop/presentation/widgets/shop_surface.dart';

void main() => runApp(const _GalleryApp());

class _GalleryApp extends StatelessWidget {
  const _GalleryApp();

  @override
  Widget build(BuildContext context) => MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: ThemeData(useMaterial3: true, fontFamily: 'Poppins'),
        home: const _Gallery(),
      );
}

class _Gallery extends StatefulWidget {
  const _Gallery();

  @override
  State<_Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<_Gallery> {
  int _qty = 3;
  bool _checked = true;
  int _reason = 1;
  bool _switchA = true;
  bool _switchB = false;
  int _chip = 0;
  int _segment = 0;

  static const _categories = ['Makanan', 'Obat & Vitamin', 'Camilan', 'Perawatan'];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: const ShopAppBar(title: 'Toko', large: true),
      bottomNavigationBar: ShopBottomBarWithTotal(
        label: 'Total · 2 barang',
        amount: 'Rp 279.000',
        action: ShopButton(
          label: 'Checkout',
          variant: ShopButtonVariant.rose,
          onTap: () {},
          padding: const EdgeInsets.symmetric(horizontal: 26, vertical: 14),
        ),
      ),
      body: ListView(
        padding: EdgeInsets.zero,
        children: [
          _label('三色分工 · 价格的三种状态'),
          ShopSection(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                _priceDemo('在售', ShopColors.rose),
                _priceDemo('已付', ShopColors.ink),
                _priceDemo('售罄', ShopColors.text4),
              ],
            ),
          ),

          _label('灰缝分区 · 3px'),
          const ShopSection(child: Text('区块一', style: ShopText.sectionTitle)),
          const ShopSection(child: Text('区块二', style: ShopText.sectionTitle)),
          ShopSection(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('区块三（内含 1px 分隔线）', style: ShopText.sectionTitle),
                const ShopDivider(margin: EdgeInsets.symmetric(vertical: 9)),
                Text('同一话题的下一行', style: ShopText.body),
              ],
            ),
          ),

          _label('按钮'),
          ShopSection(
            child: Wrap(
              spacing: 9,
              runSpacing: 9,
              children: [
                ShopButton(label: 'Bayar', variant: ShopButtonVariant.rose, onTap: () {}),
                ShopButton(
                    label: 'Lihat Alternatif', variant: ShopButtonVariant.purple, onTap: () {}),
                ShopButton(label: '+ Keranjang', variant: ShopButtonVariant.ink, onTap: () {}),
                ShopButton(
                    label: 'Batalkan',
                    variant: ShopButtonVariant.outlineMuted,
                    dense: true,
                    onTap: () {}),
                ShopButton(
                    label: 'Cari mirip',
                    variant: ShopButtonVariant.outlinePurple,
                    dense: true,
                    onTap: () {}),
                const ShopButton(label: 'Stok Habis', variant: ShopButtonVariant.disabled),
              ],
            ),
          ),
          ShopSection(
            child: ShopButton(
              label: 'Beli Sekarang',
              subtitle: 'Bayar Rp 154.000',
              variant: ShopButtonVariant.rose,
              onTap: () {},
            ),
          ),

          _label('表单控件'),
          ShopSection(
            child: Row(
              children: [
                ShopCheckbox(value: _checked, onChanged: (v) => setState(() => _checked = v)),
                const SizedBox(width: 4),
                Expanded(child: Text('可选中的勾选框（18px）', style: ShopText.body)),
                ShopStepper(
                    value: _qty, min: 1, max: 5, onChanged: (v) => setState(() => _qty = v)),
              ],
            ),
          ),
          ShopSection(
            child: Row(
              children: [
                const ShopCheckbox(value: false, enabled: false, onChanged: null),
                const SizedBox(width: 4),
                Expanded(child: Text('失效项：空块不可选 · 步进器触顶', style: ShopText.body)),
                ShopStepper(value: 5, min: 1, max: 5, onChanged: (_) {}),
              ],
            ),
          ),
          ShopSection(
            child: Column(
              children: [
                for (var i = 0; i < 2; i++) ...[
                  if (i > 0) const SizedBox(height: 7),
                  ShopRadioTile(
                    label: i == 0 ? 'Salah varian dikirim' : 'Barang rusak / kemasan bocor',
                    selected: _reason == i,
                    onTap: () => setState(() => _reason = i),
                  ),
                ],
              ],
            ),
          ),
          ShopSection(
            child: Column(
              children: [
                _switchRow('总开关（大 38×22）', ShopSwitch(
                    value: _switchA, onChanged: (v) => setState(() => _switchA = v))),
                const ShopDivider(margin: EdgeInsets.symmetric(vertical: 9)),
                _switchRow('到货通知（小 34×19）', ShopSwitch(
                    large: false,
                    value: _switchB,
                    onChanged: (v) => setState(() => _switchB = v))),
                const ShopDivider(margin: EdgeInsets.symmetric(vertical: 9)),
                _switchRow('静默期（常亮不可关）',
                    const ShopSwitch(value: true, alwaysOn: true, onChanged: null)),
              ],
            ),
          ),
          ShopSection(
            child: Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (var i = 0; i < _categories.length; i++)
                  ShopChip(
                    label: _categories[i],
                    selected: _chip == i,
                    onTap: () => setState(() => _chip = i),
                  ),
              ],
            ),
          ),
          ShopSection(
            child: ShopSegmented(
              labels: const ['Seperlunya', 'Jarang', 'Mati'],
              selectedIndex: _segment,
              onSelected: (i) => setState(() => _segment = i),
            ),
          ),

          _label('商品图 · 占位 · 售罄蒙层'),
          ShopSection(
            child: Row(
              children: [
                const ShopImage(url: null, size: 104, radius: ShopShape.radiusChip),
                const SizedBox(width: 9),
                SizedBox(
                  width: 104,
                  height: 104,
                  child: Stack(children: [
                    const ShopImage(url: null, size: 104),
                    const ShopSoldOutOverlay(
                        label: 'Stok Habis', scrim: ShopColors.soldOutScrimCard),
                    Positioned(top: 0, right: 0, child: const ShopDiscountCorner(label: '-20%')),
                  ]),
                ),
                const SizedBox(width: 9),
                ColoredBox(
                  color: ShopColors.ink,
                  child: const Padding(
                    padding: EdgeInsets.all(8),
                    child: ShopImage(
                        url: null, size: 48, radius: ShopShape.radiusButton, onInk: true),
                  ),
                ),
              ],
            ),
          ),

          _label('徽标 · 图上按钮'),
          ShopSection(
            child: Wrap(
              spacing: 9,
              runSpacing: 9,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                ShopBadge.toko('TOKO'),
                ShopBadge.service('KONSULTASI'),
                ShopBadge.recoSource('Gigi berkarang · 15 Agu'),
                const ColoredBox(
                  color: ShopColors.text3,
                  child: Padding(
                    padding: EdgeInsets.all(6),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      ShopImageButton(icon: Icons.arrow_back_ios_new, semanticLabel: '返回'),
                      SizedBox(width: 6),
                      ShopImageButton(icon: Icons.favorite_border, semanticLabel: '收藏'),
                    ]),
                  ),
                ),
              ],
            ),
          ),

          _label('左色条块 · 既定结果，不可点选'),
          ShopSection(
            child: Column(
              children: [
                ShopLeftAccentBlock.pawcoin(
                  child: _payRow('PawCoin · dipakai penuh', 'Saldo 50.000',
                      '− Rp 50.000', ShopColors.purple),
                ),
                const SizedBox(height: 7),
                ShopLeftAccentBlock.money(
                  child: _payRow('QRIS', 'Sisa tagihan dibayar di sini',
                      'Rp 154.000', ShopColors.ink),
                ),
                const SizedBox(height: 7),
                ShopLeftAccentBlock.muted(
                  child: _payRow('QRIS', 'Belum tersedia', '—', ShopColors.text4),
                ),
              ],
            ),
          ),
          const ShopSection(
            child: ShopWarnBlock(
              title: 'Bagian PawCoin tidak bisa jadi uang',
              body: 'Refund bagian PawCoin dikembalikan sebagai PawCoin, '
                  'termasuk jika pesanan dibatalkan sebelum dikirim.',
            ),
          ),

          _label('倒计时 · 等宽'),
          Container(
            color: ShopColors.rose,
            padding: const EdgeInsets.symmetric(vertical: 14),
            child: Column(
              children: [
                Text('Selesaikan pembayaran dalam',
                    style: ShopText.body.copyWith(color: ShopColors.onInk85)),
                const SizedBox(height: 2),
                ShopCountdown(
                  expiresAt: DateTime.now().toUtc().add(const Duration(minutes: 58, seconds: 12)),
                  style: ShopText.countdownHero,
                ),
              ],
            ),
          ),
          ShopSection(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('促销条内联倒计时', style: ShopText.body),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                  decoration: BoxDecoration(
                    color: ShopColors.countdownScrim,
                    borderRadius: BorderRadius.circular(ShopShape.radiusBadge),
                  ),
                  child: ShopCountdown(
                    expiresAt: DateTime.now().toUtc().add(const Duration(hours: 2, minutes: 5)),
                    style: ShopText.countdownInline.copyWith(color: ShopColors.surface),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _label(String t) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 18, 16, 7),
        child: Text(t.toUpperCase(), style: ShopText.groupHeader),
      );

  Widget _priceDemo(String tag, Color c) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(tag, style: ShopText.meta),
          Text('Rp 154.000', style: ShopText.priceGrid.copyWith(color: c)),
        ],
      );

  Widget _switchRow(String label, Widget sw) => Row(
        children: [
          Expanded(child: Text(label, style: ShopText.cardTitle)),
          sw,
        ],
      );

  Widget _payRow(String title, String sub, String amount, Color amountColor) => Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: ShopText.cardTitle),
                Text(sub, style: ShopText.meta),
              ],
            ),
          ),
          Text(amount, style: ShopText.priceInline.copyWith(color: amountColor)),
        ],
      );
}
