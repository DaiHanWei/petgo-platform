import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// analytics-pipeline Story 2.1 · Dai 拍板修订：Android 备份排除 `analytics/`（匿名 id + 本地队列），
/// 卸载重装后匿名 id 必然重新生成（A3）。静态检查清单引用与规则文件内容。
void main() {
  const res = 'android/app/src/main/res/xml';
  final manifest = File('android/app/src/main/AndroidManifest.xml').readAsStringSync();

  test('AndroidManifest <application> 引用两份备份规则，并 tools:replace 覆盖 AppsFlyer 的同名属性', () {
    expect(manifest, contains('android:fullBackupContent="@xml/backup_rules"'));
    expect(manifest, contains('android:dataExtractionRules="@xml/data_extraction_rules"'));
    expect(manifest, contains('xmlns:tools="http://schemas.android.com/tools"'));
    expect(manifest, contains('tools:replace="android:fullBackupContent,android:dataExtractionRules"'));
  });

  test('backup_rules.xml（API ≤30）排除 file:analytics/，并保留 AppsFlyer 的排除项', () {
    final xml = File('$res/backup_rules.xml').readAsStringSync();
    expect(xml, contains('<full-backup-content>'));
    expect(xml, contains('<exclude domain="file" path="analytics/"/>'));
    expect(xml, contains('<exclude domain="sharedpref" path="appsflyer-data"/>'));
  });

  test('data_extraction_rules.xml（API 31+）云备份与换机迁移都排除 file:analytics/', () {
    final xml = File('$res/data_extraction_rules.xml').readAsStringSync();
    for (final section in ['cloud-backup', 'device-transfer']) {
      final m = RegExp('<$section>([\\s\\S]*?)</$section>').firstMatch(xml);
      expect(m, isNotNull, reason: section);
      expect(m!.group(1), contains('<exclude domain="file" path="analytics/"/>'), reason: section);
      expect(m.group(1), contains('<exclude domain="sharedpref" path="appsflyer-data"/>'), reason: section);
    }
  });
}
