# app_en.arb 英文基线修正清单

**问题**：`app_en.arb` 中 38 个键的值是**印尼语**，经产品决策后实际需修正 **35 个**（地址 3 个保留原词），导致英文 locale 下界面中英印尼语混排。
**验证方式**：与 `app_id.arb` 逐键比对，值完全相同且非品牌/专有名词者即为未翻译。
**已排除**的合理同形：PawCoin / QRIS / TailTopia / Email / WhatsApp / GoPay / OVO /
Online / Offline / Normal / Rating / Refund / Bonus / Edit / Bug / KTP / Diary / Milestone / 人名。

> 实施时只改 `app_en.arb`，**不要动 `app_id.arb`**；改完跑 `flutter gen-l10n`。

## toko / 商品（21）

| 键 | 现值（印尼语） | 建议英文 |
|---|---|---|
| tokoOutOfStock | Stok habis | Out of stock |
| tokoOutOfStockLine | Stok habis. Coba varian lain. | Out of stock. Try another variant. |
| tokoLowStock | Sisa {count} | {count} left |
| tokoLowStockNoCount | Stok terbatas | Limited stock |
| tokoChooseVariant | Pilih Varian | Choose a variant |
| tokoDetailSectionTitle | Detail Produk | Product details |
| tokoShelfLifeTitle | Masa Simpan | Shelf life |
| tokoFeedingGuideTitle | Panduan Porsi Harian | Daily feeding guide |
| tokoCategoryLabel | Kategori | Category |
| tokoAllFeaturedLabel | Semua Pilihan | All picks |
| tokoReturnableTitle | Bisa diretur. | Returnable. |
| tokoReturnableBody | Produk dapat dikembalikan sesuai syarat retur. | This product can be returned under the return terms. |
| tokoNoReturnTitle | Tidak bisa diretur. | Not returnable. |
| tokoNoReturnBody | Produk ini tidak dapat dikembalikan. | This product cannot be returned. |
| tokoNoReturnAfterOpenTitle | Tidak dapat dikembalikan setelah dibuka. | Not returnable once opened. |
| tokoNoReturnAfterOpenBody | Demi keamanan pangan, kemasan makanan yang sudah dibuka tidak bisa diretur. | For food safety, opened food packaging cannot be returned. |

### 品类名（✅ 2026-09-02 产品拍板：英文界面翻译）

| 键 | 现值（印尼语） | 建议英文 |
|---|---|---|
| tokoCategoryMakanan | Makanan | Food |
| tokoCategoryObatVitamin | Obat & Vitamin | Medicine & Vitamins |
| tokoCategoryCamilan | Camilan | Snacks |
| tokoCategoryPerawatan | Perawatan | Grooming |

⚠️ 连带项：品类名同时出现在**后台**（商品管理筛选下拉、商品表单 category 选项）
与**接口枚举**（MAKANAN/OBAT_VITAMIN/CAMILAN/PERAWATAN）。
枚举值不动，只改展示层文案；后台 i18n 的对应键需一并补英文。

## cart / 购物车（8）

| 键 | 现值 | 建议英文 |
|---|---|---|
| cartTitle | Keranjang | Cart |
| cartOpen | Keranjang | Cart |
| cartTotalLabel | Total ({count} barang) | Total ({count} items) |
| cartInvalidSection | Tidak Tersedia ({count}) | Unavailable ({count}) |
| cartClearInvalid | Hapus semua | Clear all |
| cartReasonOutOfStock | Stok habis | Out of stock |
| cartReasonDelisted | Sudah ditarik | Delisted |
| cartReasonUnavailable | Tidak tersedia | Unavailable |

## order / 订单（5）

| 键 | 现值 | 建议英文 |
|---|---|---|
| orderTypeEcommerce | Belanja | Shopping |
| orderFilterKonsultasi | Konsultasi | Consultation |
| orderFilterOther | Lainnya | Other |
| orderNoPayment | Belum ada pembayaran | No payment yet |
| orderPayNow | Bayar sekarang | Pay now |

## checkout / 结算（2）

| 键 | 现值 | 建议英文 |
|---|---|---|
| checkoutShippingReguler | Reguler · 2–4 hari | Regular · 2–4 days |
| checkoutUnavailableDelisted | Sudah ditarik | Delisted |

## address / 地址（3）

✅ **2026-09-02 产品拍板：保留原词，不翻译。**
理由：印尼行政区划专名，直译会与表单实际层级对不上。

| 键 | 值 | 处理 |
|---|---|---|
| addressProvinsi | Provinsi | **保持不变** |
| addressKota | Kota / Kabupaten | **保持不变** |
| addressKecamatan | Kecamatan | **保持不变** |

⇒ 这 3 个键从「待修正」中剔除，实际需改 **35 个**。

## 其他（1）

| 键 | 现值 | 建议英文 |
|---|---|---|
| milestoneLevelChipL | L · LEGENDA | L · LEGEND |
