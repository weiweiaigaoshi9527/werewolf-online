import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/screens/admin.dart';

/// 工作流 C 的纯函数与 ApiClient 单测：批量用户操作请求体、兑换码创建请求体、
/// 以及原始字节下载接口 getBytes。全部离线，用 MockClient 喂固定响应。
void main() {
  ApiClient client(MockClient mock, {String? token}) {
    final api = ApiClient(ServerConfig(baseUrl: 'https://unit.test.invalid:11111'), client: mock);
    api.token = token;
    return api;
  }

  group('batchUserBody', () {
    test('delete 字面量必须是 "delete"（后端 switch 只认 delete，不认网页版的 del）', () {
      final b = batchUserBody([1, 2, 3], 'delete');
      expect(b['op'], 'delete');
      expect(b['ids'], [1, 2, 3]);
    });

    test('多 id 原样下发，ban/admin 的 value 为 0/1', () {
      final b = batchUserBody([11, 22, 33], 'ban', value: 1);
      expect(b['ids'], [11, 22, 33]);
      expect(b['op'], 'ban');
      expect(b['value'], 1);
      expect(batchUserBody([11], 'admin', value: 0)['value'], 0);
    });

    test('vip 的等级与天数透传，未指定时 days 默认 30', () {
      final b = batchUserBody([7], 'vip', value: 2);
      expect(b['value'], 2);
      expect(b['days'], 30);
      expect(batchUserBody([7], 'vip', value: 3, days: 0)['days'], 0);
    });

    test('gold 允许负的增减额', () {
      final b = batchUserBody([9], 'gold', value: -500);
      expect(b['value'], -500);
    });

    test('op 字面量集合恰为 ban/admin/vip/gold/delete', () {
      for (final op in ['ban', 'admin', 'vip', 'gold', 'delete']) {
        expect(batchUserBody([1], op)['op'], op);
      }
    });
  });

  group('redeemBody', () {
    test('可选项为空时不下发该键，four 个必填字段始终下发', () {
      final b = redeemBody(code: '', gold: 100, exp: 0, itemDefId: 0, maxUses: 1, expireAt: null, note: '');
      expect(b.containsKey('code'), isFalse);
      expect(b.containsKey('expireAt'), isFalse);
      expect(b.containsKey('note'), isFalse);
      expect(b['gold'], 100);
      expect(b['exp'], 0);
      expect(b['itemDefId'], 0);
      expect(b['maxUses'], 1);
    });

    test('可选项有值时原样下发，expireAt 透传 ISO 本地日期时间', () {
      final b = redeemBody(
        code: 'ABC',
        gold: 1,
        exp: 2,
        itemDefId: 3,
        maxUses: 4,
        expireAt: '2026-12-31T23:59:00',
        note: '活动',
      );
      expect(b['code'], 'ABC');
      expect(b['exp'], 2);
      expect(b['itemDefId'], 3);
      expect(b['expireAt'], '2026-12-31T23:59:00');
      expect(b['note'], '活动');
    });
  });

  group('ApiClient.getBytes', () {
    test('200 直接返回原始字节（含 BOM 原样回传）', () async {
      final bytes = [0xEF, 0xBB, 0xBF, 0x61, 0x2C, 0x62];
      final api = client(MockClient((req) async => http.Response.bytes(bytes, 200)));
      expect(await api.getBytes('/api/admin/ops/export/users'), bytes);
    });

    test('请求携带 Authorization 头', () async {
      String? auth;
      final api = client(
        MockClient((req) async {
          auth = req.headers['Authorization'];
          return http.Response.bytes([1], 200);
        }),
        token: 'tk',
      );
      await api.getBytes('/api/admin/ops/export/users');
      expect(auth, 'Bearer tk');
    });

    test('400 抛出带服务端中文 error 的 AppError', () async {
      final api = client(MockClient((req) async => http.Response(
            jsonEncode({'error': '需要管理员权限'}),
            400,
            headers: {'content-type': 'application/json'},
          )));
      expect(
        () => api.getBytes('/api/admin/ops/export/users'),
        throwsA(isA<AppError>().having((e) => e.message, 'message', '需要管理员权限')),
      );
    });
  });
}
