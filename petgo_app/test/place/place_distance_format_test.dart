import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/presentation/place_distance_format.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 batch-b1 Story 1.2 · L0：距离文案口径。
///
/// 🔴 最要紧的一条是**小数点分隔符跟设备语言走**：印尼语用逗号。
/// 硬写 `.` 会让印尼用户把 `1.2 km` 读成「12 公里」—— 会真实误导，不是排版偏好。
void main() {
  late AppLocalizations id;
  late AppLocalizations en;

  setUpAll(() async {
    id = await AppLocalizations.delegate.load(const Locale('id'));
    en = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('1 km 以内用整米', () {
    test('几百米显示米，不显示 0.x km', () {
      expect(formatPlaceDistance(id, 850), '850 m');
      expect(formatPlaceDistance(en, 850), '850 m');
    });

    test('0 米也能显示（就在脚下）', () {
      expect(formatPlaceDistance(id, 0), '0 m');
    });

    test('999 米仍是米', () {
      expect(formatPlaceDistance(id, 999), '999 m');
    });
  });

  group('1 km 以上用公里', () {
    test('🔴 印尼语用逗号作小数点', () {
      expect(formatPlaceDistance(id, 1200), '1,2 km',
          reason: '写成 1.2 km 会被印尼用户读成 12 公里');
    });

    test('英语用点', () {
      expect(formatPlaceDistance(en, 1200), '1.2 km');
    });

    /// 小数位**固定一位**（`1,0 km` 而不是 `1 km`）：一列数字里位数一致更好扫读，
    /// 而「有时带小数有时不带」会让这一列看起来在跳。
    test('正好 1000 米进入公里分支，且保留一位小数', () {
      expect(formatPlaceDistance(id, 1000), '1,0 km');
      expect(formatPlaceDistance(en, 1000), '1.0 km');
    });

    test('12 km 以上不带小数（那个 .4 不改变任何决定）', () {
      expect(formatPlaceDistance(id, 12_400), '12 km');
      expect(formatPlaceDistance(en, 45_600), '46 km');
    });
  });
}
