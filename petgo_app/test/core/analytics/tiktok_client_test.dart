import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/tiktok_client.dart';

void main() {
  test('debug 构建默认不初始化（防本地装机污染投放归因），release 才初始化', () {
    expect(TikTokClient.shouldStart(debug: true), isFalse);
    expect(TikTokClient.shouldStart(debug: false), isTrue);
  });

  test('测试环境（debug）：start / setUserId / clearUserId 全部静默不抛', () async {
    await TikTokClient.instance.start();
    await TikTokClient.instance.setUserId('abc');
    await TikTokClient.instance.clearUserId();
  });
}
