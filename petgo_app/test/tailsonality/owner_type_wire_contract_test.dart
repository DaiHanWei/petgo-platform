import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';

/// V1.3.2 Story 2.5 · AC1.5：主人类型线格式（与后端 `TailsonalityOwnerTypeResponseContractTest` 同集 `{typeCode}`）。
void main() {
  test('{typeCode} → 值；{} → null；非法值 fail-closed 为 null', () {
    expect(ownerTypeFromJson({'typeCode': 'INFP'}), 'INFP');
    expect(ownerTypeFromJson(const {}), isNull);
    expect(ownerTypeFromJson(null), isNull);
    expect(ownerTypeFromJson({'typeCode': 'INFP-H'}), isNull);
    expect(ownerTypeFromJson({'typeCode': 1}), isNull);
  });

  test('仓库：GET / PUT 路径与请求体', () async {
    final sent = <RequestOptions>[];
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
        sent.add(o);
        h.resolve(Response(
            requestOptions: o, statusCode: 200, data: o.method == 'PUT' ? {'typeCode': 'ENTJ'} : <String, dynamic>{}));
      }));
    final repo = TailsonalityOwnerTypeRepository(dio: dio);
    expect(await repo.fetch(), isNull);
    expect(await repo.save('ENTJ'), 'ENTJ');
    expect(sent.map((o) => o.method), ['GET', 'PUT']);
    expect(sent.every((o) => o.path == ApiPaths.meTailsonalityOwnerType), isTrue);
    expect(ApiPaths.meTailsonalityOwnerType, endsWith('/api/v1/me/tailsonality/owner-type'));
    expect(sent.last.data, {'typeCode': 'ENTJ'});
  });
}
