import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:esp32_drone_controller/services/drone_link.dart';

void main() {
  test(
    'actual WebSocket handshake, authentication, sequence and clock freshness',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final received = <Map<String, dynamic>>[];
      final authenticated = Completer<void>();
      WebSocket? socket;
      final listener = server.listen((request) async {
        socket = await WebSocketTransformer.upgrade(request);
        socket!.listen((dynamic data) {
          final packet = jsonDecode(data as String) as Map<String, dynamic>;
          received.add(packet);
          if (packet['type'] == 'auth') {
            socket!.add(
              jsonEncode({
                'v': 1,
                'type': 'auth_ok',
                'session': 'new-session',
                'now_ms': 12345,
              }),
            );
          } else if (received.length >= 3 && !authenticated.isCompleted) {
            authenticated.complete();
          }
        });
      });
      final link = DroneLink();
      link.addListener(() {
        if (link.authenticated && received.length == 1) {
          link.send('heartbeat');
          link.send('control', {
            'throttle': .2,
            'roll': 0,
            'pitch': 0,
            'yaw': 0,
          });
        }
      });
      try {
        await link.connect(
          'ws://127.0.0.1:${server.port}/ws',
          'local-only-test-token',
        );
        await authenticated.future.timeout(const Duration(seconds: 3));
        expect(received[0], {
          'v': 1,
          'type': 'auth',
          'token': 'local-only-test-token',
        });
        expect(received[1]['session'], 'new-session');
        expect(received[1]['seq'], 1);
        expect(received[2]['seq'], 2);
        expect(received[2]['sent_ms'], inInclusiveRange(12345, 13000));
        expect(received[2].containsKey('token'), false);
        await link.disconnect();
        expect(link.send('arm'), false);
      } finally {
        link.dispose();
        await socket?.close();
        await listener.cancel();
        await server.close(force: true);
      }
    },
  );
}
