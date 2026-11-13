import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/presentation/product_detail_page_v3.dart';

/// 修复线 2026-11-13 实机反馈：商品详情列表项之间各空一行，行距比字还显眼。
void main() {
  test('<ul><li> 列表：每条一行，条目之间不空行', () {
    expect(
      stripShopHtml('<p>Informasi Produk</p><ul><li>Merek: Perro</li><li>Kategori: Camilan</li><li>Netto: 70 gr</li></ul>'),
      'Informasi Produk\n• Merek: Perro\n• Kategori: Camilan\n• Netto: 70 gr',
    );
  });

  test('段落里自带「•」且段落间有空段：同样不空行', () {
    expect(
      stripShopHtml('<p>Informasi Produk</p><p><br></p><p>• Merek: Perro</p><p><br></p><p>• Kategori: Camilan</p>'),
      'Informasi Produk\n• Merek: Perro\n• Kategori: Camilan',
    );
  });

  test('普通段落之间的空行保留（作者有意分段）', () {
    expect(stripShopHtml('<p>Deskripsi</p><p><br></p><p>Lanjutan</p>'), 'Deskripsi\n\nLanjutan');
  });
}
