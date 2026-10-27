import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:web_socket_channel/web_socket_channel.dart';
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

  test(
    'newest rapid connect owns socket before asynchronous teardown',
    () async {
      final created = <FakeChannel>[];
      final endpoints = <Uri>[];
      final link = DroneLink(
        channelFactory: (uri) {
          endpoints.add(uri);
          final channel = FakeChannel(ready: true);
          created.add(channel);
          return channel;
        },
      );
      final first = link.connect('ws://192.168.4.1:81/ws', 'first');
      final second = link.connect('ws://127.0.0.1:81/ws', 'second');
      await Future.wait([first, second]);
      expect(created.length, 1);
      expect(endpoints.single.host, '127.0.0.1');
      expect(jsonDecode(created.single.output.sent.single)['token'], 'second');
      link.dispose();
      await pumpEvents();
      expect(created.single.output.closeCalls, 1);
    },
  );

  test('dispose before connect continuation cannot create socket', () async {
    var creations = 0;
    final link = DroneLink(
      channelFactory: (_) {
        creations++;
        return FakeChannel(ready: true);
      },
    );
    final connecting = link.connect('ws://192.168.4.1:81/ws', 'token');
    link.dispose();
    await connecting;
    expect(creations, 0);
    expect(link.authenticated, false);
    expect(link.send('arm'), false);
    await link.connect('ws://192.168.4.1:81/ws', 'token');
    expect(creations, 0);
  });

  test(
    'dispose cancels pending handshake and consumes its late error',
    () async {
      final channel = FakeChannel();
      final link = DroneLink(channelFactory: (_) => channel);
      final connecting = link.connect('ws://192.168.4.1:81/ws', 'token');
      await pumpEvents();
      link.dispose();
      await connecting.timeout(const Duration(milliseconds: 200));
      channel.readyResult.completeError(StateError('late handshake error'));
      await pumpEvents();
      expect(channel.output.closeCalls, 1);
      expect(channel.output.sent, isEmpty);
    },
  );

  test(
    'dispose while reconnect teardown pending never opens replacement',
    () async {
      final cancelled = Completer<void>(), closed = Completer<void>();
      final firstChannel = FakeChannel(
        ready: true,
        cancellation: cancelled,
        closure: closed,
      );
      var creations = 0;
      final link = DroneLink(
        channelFactory: (_) {
          creations++;
          return firstChannel;
        },
      );
      await link.connect('ws://192.168.4.1:81/ws', 'first');
      final replacement = link.connect('ws://192.168.4.1:81/ws', 'second');
      link.dispose();
      cancelled.complete();
      closed.complete();
      await replacement;
      expect(creations, 1);
      expect(firstChannel.output.closeCalls, 1);
    },
  );

  test('third connect supersedes second blocked by old teardown', () async {
    final cancelled = Completer<void>(), closed = Completer<void>();
    final channels = <FakeChannel>[
      FakeChannel(ready: true, cancellation: cancelled, closure: closed),
      FakeChannel(ready: true),
    ];
    var creations = 0;
    final link = DroneLink(channelFactory: (_) => channels[creations++]);
    await link.connect('ws://192.168.4.1:81/ws', 'first');
    final second = link.connect('ws://192.168.4.1:81/ws', 'second');
    final third = link.connect('ws://192.168.4.1:81/ws', 'third');
    await third;
    expect(creations, 2);
    expect(jsonDecode(channels[1].output.sent.single)['token'], 'third');
    channels[1].incoming.add(auth('third-session'));
    await pumpEvents();
    cancelled.complete();
    closed.complete();
    await second;
    expect(link.session, 'third-session');
    expect(creations, 2);
    link.dispose();
    await pumpEvents();
  });

  test(
    'failed ready and stream both handled without unhandled errors',
    () async {
      final channel = FakeChannel();
      final link = DroneLink(channelFactory: (_) => channel);
      final connecting = link.connect('ws://192.168.4.1:81/ws', 'token');
      await pumpEvents();
      channel.readyResult.completeError(StateError('handshake failed'));
      channel.incoming.addError(StateError('same socket error'));
      await connecting;
      await pumpEvents();
      expect(link.authenticated, false);
      expect(link.status, anyOf('Connection error', 'Connection failed'));
      expect(channel.output.closeCalls, 1);
      link.dispose();
    },
  );

  test('stuck cancel and close are bounded and late errors consumed', () async {
    final cancelled = Completer<void>(), closed = Completer<void>();
    final channels = <FakeChannel>[
      FakeChannel(ready: true, cancellation: cancelled, closure: closed),
      FakeChannel(ready: true),
    ];
    var creations = 0;
    final link = DroneLink(
      channelFactory: (_) => channels[creations++],
      teardownTimeout: const Duration(milliseconds: 20),
    );
    await link.connect('ws://192.168.4.1:81/ws', 'first');
    await link
        .connect('ws://192.168.4.1:81/ws', 'second')
        .timeout(const Duration(milliseconds: 200));
    expect(creations, 2);
    expect(channels[0].output.closeCalls, 1);
    cancelled.completeError(StateError('late cancellation error'));
    closed.completeError(StateError('late close error'));
    await pumpEvents();
    link.dispose();
    await pumpEvents();
  });

  test('finishing old disconnect cannot overwrite a new session', () async {
    final cancelled = Completer<void>(), closed = Completer<void>();
    final channels = <FakeChannel>[
      FakeChannel(ready: true, cancellation: cancelled, closure: closed),
      FakeChannel(ready: true),
    ];
    var creations = 0;
    final link = DroneLink(channelFactory: (_) => channels[creations++]);
    await link.connect('ws://192.168.4.1:81/ws', 'first');
    final disconnecting = link.disconnect();
    await link.connect('ws://192.168.4.1:81/ws', 'second');
    channels[1].incoming.add(auth('new-session'));
    await pumpEvents();
    cancelled.complete();
    closed.complete();
    await disconnecting;
    expect(link.status, 'Authenticated');
    expect(link.session, 'new-session');
    expect(link.send('heartbeat'), true);
    expect(jsonDecode(channels[1].output.sent.last)['seq'], 1);
    link.dispose();
    await pumpEvents();
  });

  test(
    'connection and authentication timeouts close without reconnect',
    () async {
      var creations = 0;
      final channel = FakeChannel();
      final link = DroneLink(
        channelFactory: (_) {
          creations++;
          return channel;
        },
        connectionTimeout: const Duration(milliseconds: 20),
      );
      await link.connect('ws://192.168.4.1:81/ws', 'token');
      expect(link.status, 'Connection timed out');
      expect(channel.output.closeCalls, 1);
      expect(creations, 1);
      channel.readyResult.completeError(StateError('late failure'));
      await pumpEvents();
      link.dispose();

      final readyChannel = FakeChannel(ready: true);
      final authLink = DroneLink(
        channelFactory: (_) => readyChannel,
        authenticationTimeout: const Duration(milliseconds: 20),
      );
      await authLink.connect('ws://192.168.4.1:81/ws', 'token');
      await Future<void>.delayed(const Duration(milliseconds: 40));
      expect(authLink.status, 'Authentication timed out');
      expect(authLink.authenticated, false);
      expect(readyChannel.output.closeCalls, 1);
      authLink.dispose();
    },
  );

  test(
    'scheduled disposal or disposal from telemetry creates no late notify',
    () async {
      var creations = 0;
      final link = DroneLink(
        channelFactory: (_) {
          creations++;
          return FakeChannel(ready: true);
        },
      );
      link.addListener(() => scheduleMicrotask(link.dispose));
      await link.connect('ws://192.168.4.1:81/ws', 'token');
      expect(creations, 0);

      final channel = FakeChannel(ready: true);
      final telemetryLink = DroneLink(channelFactory: (_) => channel);
      await telemetryLink.connect('ws://192.168.4.1:81/ws', 'token');
      telemetryLink.onMessage = (_) => telemetryLink.dispose();
      channel.incoming.add(auth('session'));
      await pumpEvents();
      expect(telemetryLink.authenticated, false);
      expect(channel.output.closeCalls, 1);
    },
  );
}

Future<void> pumpEvents() => Future<void>.delayed(Duration.zero);
String auth(String session) =>
    jsonEncode({'v': 1, 'type': 'auth', 'session': session, 'now_ms': 1000});

class FakeChannel implements WebSocketChannel {
  FakeChannel({
    bool ready = false,
    Completer<void>? cancellation,
    Completer<void>? closure,
  }) : output = FakeSink(closure) {
    incoming = StreamController<dynamic>(
      sync: true,
      onCancel: () => cancellation?.future,
    );
    if (ready) readyResult.complete();
  }
  final readyResult = Completer<void>();
  late final StreamController<dynamic> incoming;
  final FakeSink output;
  @override
  Future<void> get ready => readyResult.future;
  @override
  Stream<dynamic> get stream => incoming.stream;
  @override
  WebSocketSink get sink => output;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class FakeSink implements WebSocketSink {
  FakeSink(this.closure);
  final Completer<void>? closure;
  final sent = <String>[];
  int closeCalls = 0;
  @override
  void add(dynamic value) => sent.add(value as String);
  @override
  void addError(Object error, [StackTrace? stackTrace]) {}
  @override
  Future<void> addStream(Stream<dynamic> stream) async {
    await for (final value in stream) {
      add(value);
    }
  }

  @override
  Future<void> close([int? closeCode, String? closeReason]) {
    closeCalls++;
    return closure?.future ?? Future<void>.value();
  }

  @override
  Future<void> get done => closure?.future ?? Future<void>.value();
}
