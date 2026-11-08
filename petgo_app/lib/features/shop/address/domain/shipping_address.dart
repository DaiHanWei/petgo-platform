/// 收货地址域模型（Story 2.4，消费 2-1 的 `/api/v1/me/shipping-addresses`）。
library;

/// 🔒 本类含三项 PII（收件人姓名 / 履约电话 / 详细地址）——
/// 🔴 **不要给它写 toString、不要塞进埋点或日志**（NFR-5）。
class ShippingAddress {
  const ShippingAddress({
    required this.token,
    required this.receiverName,
    required this.receiverPhone,
    required this.provinsi,
    required this.kotaKabupaten,
    required this.kecamatan,
    required this.addressLine,
    required this.kodePos,
    required this.isDefault,
    this.label,
    this.serviceable,
  });

  final String token;
  final String receiverName;
  final String receiverPhone;
  final String provinsi;
  final String kotaKabupaten;
  final String kecamatan;
  final String addressLine;
  final String kodePos;
  final String? label;
  final bool isDefault;

  /// v1.4.2 shop Story 3.1（D-9）：地址簿列表的可配送标注。false = 不可配送；true = 可配送；
  /// null = 未判定（询价失败 / 其他出口不下发）。🔴 只读，[toRequestJson] 不写出。
  final bool? serviceable;

  factory ShippingAddress.fromJson(Map<String, dynamic> j) => ShippingAddress(
        token: j['token']?.toString() ?? '',
        receiverName: j['receiverName']?.toString() ?? '',
        receiverPhone: j['receiverPhone']?.toString() ?? '',
        provinsi: j['provinsi']?.toString() ?? '',
        kotaKabupaten: j['kotaKabupaten']?.toString() ?? '',
        kecamatan: j['kecamatan']?.toString() ?? '',
        addressLine: j['addressLine']?.toString() ?? '',
        kodePos: j['kodePos']?.toString() ?? '',
        label: (j['label']?.toString().isEmpty ?? true) ? null : j['label'].toString(),
        isDefault: j['isDefault'] == true,
        serviceable: j['serviceable'] is bool ? j['serviceable'] as bool : null,
      );

  Map<String, dynamic> toRequestJson() => {
        'receiverName': receiverName,
        'receiverPhone': receiverPhone,
        'provinsi': provinsi,
        'kotaKabupaten': kotaKabupaten,
        'kecamatan': kecamatan,
        'addressLine': addressLine,
        'kodePos': kodePos,
        'label': label,
      };

  /// 🔒 只暴露非 PII 字段，防止有人 print 出去。
  @override
  String toString() => 'ShippingAddress[$token, $kecamatan, PII omitted]';
}

/// 行政区划三级树。
class RegionTree {
  const RegionTree(this.provinsi);

  final List<RegionProvinsi> provinsi;

  factory RegionTree.fromJson(Map<String, dynamic> j) => RegionTree(
        (j['provinsi'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(RegionProvinsi.fromJson)
            .toList(),
      );
}

class RegionProvinsi {
  const RegionProvinsi(this.name, this.kota);

  final String name;
  final List<RegionKota> kota;

  factory RegionProvinsi.fromJson(Map<String, dynamic> j) => RegionProvinsi(
        j['name']?.toString() ?? '',
        (j['kota'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(RegionKota.fromJson)
            .toList(),
      );
}

class RegionKota {
  const RegionKota(this.name, this.kecamatan);

  final String name;
  final List<RegionKecamatan> kecamatan;

  factory RegionKota.fromJson(Map<String, dynamic> j) => RegionKota(
        j['name']?.toString() ?? '',
        (j['kecamatan'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(RegionKecamatan.fromJson)
            .toList(),
      );
}

class RegionKecamatan {
  const RegionKecamatan(this.name, this.serviceable, {this.id});

  final String name;

  /// false = 平台已录入但当前不可送达。🔴 仍要让用户选得到（FR-99 允许存超范围地址）。
  final bool serviceable;

  /// `shipping_zones.id`（v1.4.2 shop Story 3.1 / C-9：区域参考数据，允许外露）。
  /// 详情页 / 购物车按地区估运费时作 `regionKecamatanId`。旧后端缺键 → null。
  final int? id;

  factory RegionKecamatan.fromJson(Map<String, dynamic> j) => RegionKecamatan(
        j['name']?.toString() ?? '',
        j['serviceable'] == true,
        id: j['id'] is num ? (j['id'] as num).toInt() : null,
      );
}

/// 印尼手机号归一化 —— 🔴 **口径必须与服务端 `IndonesiaPhone` 完全一致**（C-15）。
///
/// 两边不一致时，用户在 App 里通过了、提交却被服务端拒，是最难排查的一类问题：
/// 表面看是"保存失败"，实际是两套规则打架。因此本函数与服务端<b>逐条对齐</b>：
/// `+62` + 9~12 位有效位、首位必为 8、自动剥前导 0。
String? normalizeIdPhone(String raw) {
  var digits = raw.replaceAll(RegExp(r'[^0-9+]'), '');
  if (digits.startsWith('+62')) {
    digits = digits.substring(3);
  } else if (digits.startsWith('62')) {
    digits = digits.substring(2);
  }
  digits = digits.replaceAll('+', '');
  while (digits.startsWith('0')) {
    digits = digits.substring(1);
  }
  if (!RegExp(r'^8[0-9]{8,11}$').hasMatch(digits)) return null;
  return '+62$digits';
}
