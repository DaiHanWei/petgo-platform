import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/meta_client.dart';

void main() {
  test('debug 构建默认不向 Meta 发事件（防本地装机污染投放归因），release 才发', () {
    expect(MetaClient.shouldReport(debug: true), isFalse);
    expect(MetaClient.shouldReport(debug: false), isTrue);
  });

  test('回传给 Meta / TikTok 的注册事件 = AppsFlyer 注册完成同名事件（单一触发点）', () {
    expect(Analytics.adRegistrationEvent, 'af_complete_registration');
    expect(Analytics.isAppsFlyerEvent(Analytics.adRegistrationEvent), isTrue);
  });

  test('测试环境（debug）：所有调用静默不抛', () async {
    await MetaClient.instance.setUserId('abc');
    await MetaClient.instance.logRegistration('google');
    await MetaClient.instance.clearUserId();
  });
}
