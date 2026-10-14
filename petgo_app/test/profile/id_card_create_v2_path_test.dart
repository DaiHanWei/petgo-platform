import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';

/// V1.3.4 ktp D-20：新版 App 建卡走 V2 接口（必填由服务端强制）；旧路径的 POST 留给 1.3.3 及更早 App。
void main() {
  test('建卡 V2 路径', () {
    expect(ApiPaths.meIdCardsCreateV2, endsWith('/api/v1/pet-profiles/me/id-cards/v2'));
  });

  test('仓库建卡调用 V2，不再 POST 旧路径', () {
    final src = File('lib/features/profile/data/id_card_repository.dart').readAsStringSync();
    expect(src, contains('dio.post<Map<String, dynamic>>(ApiPaths.meIdCardsCreateV2'));
    expect(src, isNot(contains('dio.post<Map<String, dynamic>>(ApiPaths.meIdCards,')));
  });
}
