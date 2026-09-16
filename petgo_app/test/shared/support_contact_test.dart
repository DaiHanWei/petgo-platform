import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/shared/config/support_contact.dart';
import 'package:tailtopia/shared/config/support_contact_repository.dart';

/// L0：客服联系方式（Story 3-1 AC7）。纯函数式，不 `pumpWidget`。
///
/// 🔴 本类的全部重点是**兜底**：客服号是「其它路都走不通时」用户最后能抓住的东西。
/// provider 进 error 态会让弹窗画出空白或错误块，等于把最后一条路也堵死 ——
/// 失败的正确表现是「显示一个可能有点旧、但一定能打通的号码」。
void main() {
  /// 让 provider 用一个我们说了算的 repository。
  ProviderContainer containerWith(SupportContactRepository repo) {
    final c = ProviderContainer(
      overrides: [supportContactRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(c.dispose);
    return c;
  }

  group('🔴 provider 永不进 error 态', () {
    test('网络异常 → 回退兜底常量', () async {
      final c = containerWith(_ThrowingRepo(
          DioException(requestOptions: RequestOptions(path: ApiPaths.supportContact))));

      await expectLater(
          c.read(supportContactProvider.future), completion(kFallbackSupportContact));
    });

    test('任意非 Dio 异常（解析失败之类）→ 同样回退，不上抛', () async {
      final c = containerWith(_ThrowingRepo(StateError('boom')));

      await expectLater(
          c.read(supportContactProvider.future), completion(kFallbackSupportContact));
    });

    test('成功时用服务端下发的值，不用兜底', () async {
      const fresh = SupportContact(
        whatsappNumber: '081399779133',
        whatsappE164: '+6281399779133',
        email: 'halo@tailtopia.id',
      );
      final c = containerWith(_FixedRepo(fresh));

      await expectLater(c.read(supportContactProvider.future), completion(fresh));
    });
  });

  group('fromJson 逐字段兜底', () {
    test('三个字段齐全 → 原样取', () {
      final c = SupportContact.fromJson(const {
        'whatsappNumber': '081234567890',
        'whatsappE164': '+6281234567890',
        'email': 'halo@tailtopia.id',
      });

      expect(c.whatsappNumber, '081234567890');
      expect(c.whatsappE164, '+6281234567890');
      expect(c.email, 'halo@tailtopia.id');
    });

    test('🔴 两个号码字段一起兜底 —— 绝不出现「显示新号、深链旧号」', () {
      // 独立回退会造出「看到一个号码、点下去联系到另一个人」，比整条用旧值糟得多。
      final c = SupportContact.fromJson(const {
        'whatsappNumber': '081234567890',
        'whatsappE164': '',
      });

      expect(c.whatsappNumber, kFallbackSupportContact.whatsappNumber);
      expect(c.whatsappE164, kFallbackSupportContact.whatsappE164);
      expect(c.email, kFallbackSupportContact.email, reason: '缺键按「没有」处理');
    });

    test('邮箱与号码无耦合，可以各自兜底', () {
      final c = SupportContact.fromJson(const {
        'whatsappNumber': '081234567890',
        'whatsappE164': '+6281234567890',
        // email 缺失
      });

      expect(c.whatsappNumber, '081234567890', reason: '号码对齐时不该被兜底盖掉');
      expect(c.whatsappE164, '+6281234567890');
      expect(c.email, kFallbackSupportContact.email);
    });

    test('整个响应体是空 map → 三个字段全兜底，不抛', () {
      expect(SupportContact.fromJson(const {}), kFallbackSupportContact);
    });
  });

  group('兜底常量本身', () {
    test('E.164 带 + —— wa.me 要剥 + 是 URL 构造方的事（Story 3-3）', () {
      expect(kFallbackSupportContact.whatsappE164, startsWith('+'));
      expect(kFallbackSupportContact.whatsappE164, '+6281290906953');
    });

    test('与后端 SupportContactProvider.Default 及迁移种子同值', () {
      expect(kFallbackSupportContact.whatsappNumber, '081290906953');
      expect(kFallbackSupportContact.email, 'cs@tailtopia.id');
    });
  });

  test('端点路径是免鉴权的那一条', () {
    expect(ApiPaths.supportContact, '/api/v1/support/contact');
  });
}

class _ThrowingRepo implements SupportContactRepository {
  _ThrowingRepo(this.error);

  final Object error;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<SupportContact> fetch() async => throw error;
}

class _FixedRepo implements SupportContactRepository {
  _FixedRepo(this.value);

  final SupportContact value;

  @override
  Dio get dio => throw UnimplementedError();

  @override
  Future<SupportContact> fetch() async => value;
}
