import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';

/// Story 2.4：地址簿域逻辑。
///
/// 🔴 **手机号归一化必须与服务端 `IndonesiaPhone` 逐条一致**（C-15）——
/// 两边不一致时用户在 App 里通过了、提交却被服务端拒，表面看是「保存失败」，
/// 实际是两套规则打架，是最难排查的一类问题。本组用例与后端
/// `IndonesiaPhoneTest` 的断言**刻意保持镜像**。
void main() {
  group('normalizeIdPhone 与服务端口径一致（C-15）', () {
    test('三种输入形式归一到同一值', () {
      expect(normalizeIdPhone('08123456789'), '+628123456789');
      expect(normalizeIdPhone('8123456789'), '+628123456789');
      expect(normalizeIdPhone('+62 812-3456-789'), '+628123456789');
    });

    test('🔴 前导 0 被剥掉（不剥会存成 +6208...，快递系统拨不通）', () {
      expect(normalizeIdPhone('008123456789'), '+628123456789');
      expect(normalizeIdPhone('08123456789')!.startsWith('+620'), isFalse);
    });

    test('🔴 下限 9 位不是 8 位（8 位放进的是无效号 = 履约失败）', () {
      expect(normalizeIdPhone('81234567'), isNull);       // 8 位
      expect(normalizeIdPhone('812345678'), '+62812345678'); // 9 位
    });

    test('上限 12 位：12 通过、13 拒', () {
      expect(normalizeIdPhone('812345678901'), '+62812345678901');
      expect(normalizeIdPhone('8123456789012'), isNull);
    });

    test('首位必须是 8（固话被拒）', () {
      expect(normalizeIdPhone('7123456789'), isNull);
      expect(normalizeIdPhone('+62 21 1234567'), isNull);
    });

    test('空/非法输入返回 null 而非抛错', () {
      expect(normalizeIdPhone(''), isNull);
      expect(normalizeIdPhone('abc'), isNull);
    });
  });

  group('ShippingAddress', () {
    test('🔒 toString 不泄露三项 PII', () {
      const a = ShippingAddress(
        token: 'tok', receiverName: 'Budi Santoso', receiverPhone: '+628123456789',
        provinsi: 'DKI Jakarta', kotaKabupaten: 'Jakarta Selatan',
        kecamatan: 'Kebayoran Baru', addressLine: 'Jl. Melawai IV No. 12',
        kodePos: '12160', isDefault: true,
      );
      final s = a.toString();
      expect(s, isNot(contains('Budi Santoso')));
      expect(s, isNot(contains('+628123456789')));
      expect(s, isNot(contains('Jl. Melawai')));
      // 非 PII 的定位信息保留，便于排障
      expect(s, contains('Kebayoran Baru'));
    });

    test('fromJson 缺字段不崩；空 label 归一为 null', () {
      final a = ShippingAddress.fromJson({'token': 't', 'label': ''});
      expect(a.label, isNull);
      expect(a.isDefault, isFalse);
      expect(a.receiverName, isEmpty);
    });
  });

  group('RegionTree', () {
    test('三级树解析，serviceable 缺省为 false（保守）', () {
      final t = RegionTree.fromJson({
        'provinsi': [
          {'name': 'DKI Jakarta', 'kota': [
            {'name': 'Jakarta Selatan', 'kecamatan': [
              {'name': 'Kebayoran Baru', 'serviceable': true},
              {'name': 'Pesanggrahan'},
            ]},
          ]},
        ],
      });
      expect(t.provinsi, hasLength(1));
      expect(t.provinsi.first.kota.first.kecamatan, hasLength(2));
      expect(t.provinsi.first.kota.first.kecamatan[0].serviceable, isTrue);
      expect(t.provinsi.first.kota.first.kecamatan[1].serviceable, isFalse);
    });

    test('空树不崩', () {
      expect(RegionTree.fromJson({}).provinsi, isEmpty);
    });
  });
}
