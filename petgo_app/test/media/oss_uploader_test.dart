import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/data/sts_credential.dart';

void main() {
  group('OSS V1 签名（纯函数）', () {
    test('stringToSign 含 VERB / Content-Type / Date / security-token / 资源路径', () {
      final s = OssUploader.stringToSign(
        verb: 'PUT',
        contentType: 'image/jpeg',
        date: 'Mon, 02 Jun 2026 00:00:00 GMT',
        securityToken: 'tok123',
        bucket: 'petgo-public',
        objectKey: 'public/42/abc.jpg',
      );
      expect(s, startsWith('PUT\n'));
      expect(s, contains('image/jpeg'));
      expect(s, contains('x-oss-security-token:tok123\n'));
      expect(s, endsWith('/petgo-public/public/42/abc.jpg'));
      // 私有域（无 objectAcl）不含 x-oss-object-acl 头。
      expect(s, isNot(contains('x-oss-object-acl')));
    });

    test('公开域 objectAcl=public-read 计入签名串且按字母序在 security-token 之前', () {
      final s = OssUploader.stringToSign(
        verb: 'PUT',
        contentType: 'image/jpeg',
        date: 'Mon, 02 Jun 2026 00:00:00 GMT',
        securityToken: 'tok123',
        bucket: 'tailtopia',
        objectKey: 'public/42/abc.jpg',
        objectAcl: 'public-read',
      );
      expect(s, contains('x-oss-object-acl:public-read\n'));
      // OSS 要求 x-oss-* 按字母序：object-acl 必须排在 security-token 之前。
      expect(
        s.indexOf('x-oss-object-acl:'),
        lessThan(s.indexOf('x-oss-security-token:')),
      );
    });

    test('authorization 形如 "OSS <ak>:<sig>" 且对相同输入稳定', () {
      const sts = 'PUT\n\nimage/jpeg\nDATE\nx-oss-security-token:t\n/b/k';
      final a1 = OssUploader.authorization(accessKeyId: 'AK', accessKeySecret: 'SK', stringToSign: sts);
      final a2 = OssUploader.authorization(accessKeyId: 'AK', accessKeySecret: 'SK', stringToSign: sts);
      expect(a1, startsWith('OSS AK:'));
      expect(a1, a2);
      // 不同密钥 → 不同签名
      final a3 = OssUploader.authorization(accessKeyId: 'AK', accessKeySecret: 'OTHER', stringToSign: sts);
      expect(a1, isNot(a3));
    });
  });

  group('对象 key / 上传 URL', () {
    test('buildObjectKey 落在 uploadDir 前缀下、带扩展名、不可枚举', () {
      final k1 = OssUploader.buildObjectKey('public/42/', extension: 'jpg');
      final k2 = OssUploader.buildObjectKey('public/42/', extension: 'jpg');
      expect(k1, startsWith('public/42/'));
      expect(k1, endsWith('.jpg'));
      expect(k1, isNot(k2)); // 随机不可枚举
    });

    test('uploadUrl 为虚拟主机式 bucket.host', () {
      const cred = StsCredential(
        accessKeyId: 'ak',
        accessKeySecret: 'sk',
        securityToken: 'st',
        expiration: '',
        bucket: 'petgo-public',
        region: 'ap-southeast-5',
        endpoint: 'https://oss-ap-southeast-5.aliyuncs.com',
        uploadDir: 'public/42/',
      );
      final url = OssUploader.uploadUrl(cred, 'public/42/x.jpg');
      expect(url, 'https://petgo-public.oss-ap-southeast-5.aliyuncs.com/public/42/x.jpg');
    });
  });

  test('StsCredential.fromJson 解析公开/私密信封', () {
    final pub = StsCredential.fromJson({
      'accessKeyId': 'ak',
      'accessKeySecret': 'sk',
      'securityToken': 'st',
      'expiration': '2026-06-02T00:00:00Z',
      'bucket': 'petgo-public',
      'region': 'ap-southeast-5',
      'endpoint': 'https://ep',
      'cdnBaseUrl': 'https://cdn',
      'uploadDir': 'public/42/',
    });
    expect(pub.cdnBaseUrl, 'https://cdn');

    final priv = StsCredential.fromJson({
      'accessKeyId': 'ak',
      'accessKeySecret': 'sk',
      'securityToken': 'st',
      'bucket': 'petgo-private',
      'endpoint': 'https://ep',
      'uploadDir': 'private/42/',
    });
    expect(priv.cdnBaseUrl, isNull);
  });
}
