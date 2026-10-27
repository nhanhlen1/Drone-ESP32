import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// One authenticated session. No automatic reconnect or restored ARM state.
class DroneLink extends ChangeNotifier {
  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _subscription;
  Timer? _authTimeout;
  int _generation = 0;
  bool _disposed = false;
  String? session;
  String status = 'Disconnected';
  String lastAck = '—';
  final Stopwatch _clock = Stopwatch()..start();
  int _remoteAtAuth = 0, _localAtAuth = 0, _sequence = 0;
  int receivedPackets = 0;
  final List<String> errors = [];
  void Function(Map<String, dynamic>)? onMessage;
  bool get authenticated => session != null;
  int get remoteNow =>
      (_remoteAtAuth + _clock.elapsedMilliseconds - _localAtAuth) & 0xffffffff;

  Future<void> connect(String endpoint, String token) async {
    await disconnect();
    final generation = ++_generation;
    final uri = Uri.tryParse(endpoint);
    if (uri == null ||
        !['ws', 'wss'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.query.isNotEmpty ||
        token.isEmpty) {
      throw const FormatException(
        'Enter a ws:// or wss:// endpoint and session token.',
      );
    }
    status = 'Connecting';
    notifyListeners();
    try {
      final channel = WebSocketChannel.connect(uri);
      _channel = channel;
      await channel.ready.timeout(const Duration(seconds: 5));
      if (generation != _generation || _disposed) return;
      status = 'Authenticating';
      _subscription = channel.stream.listen(
        (data) {
          if (generation == _generation && !_disposed) _receive(data);
        },
        onError: (Object error) {
          if (generation == _generation && !_disposed) {
            _fail('Connection error');
          }
        },
        onDone: () {
          if (generation == _generation && !_disposed) {
            _fail('Connection closed');
          }
        },
      );
      channel.sink.add(jsonEncode({'v': 1, 'type': 'auth', 'token': token}));
      _authTimeout = Timer(const Duration(seconds: 5), () {
        if (generation == _generation && !authenticated && !_disposed) {
          _fail('Authentication timed out');
        }
      });
      notifyListeners();
    } catch (error) {
      if (generation == _generation && !_disposed) _fail('Connection failed');
      rethrow;
    }
  }

  void _receive(dynamic data) {
    try {
      if (data is! String || data.length > 16384) return;
      final decoded = jsonDecode(data);
      if (decoded is! Map<String, dynamic> || decoded['v'] != 1) return;
      receivedPackets++;
      if ((decoded['type'] == 'auth' || decoded['type'] == 'auth_ok') &&
          decoded['session'] is String &&
          (decoded['session'] as String).isNotEmpty &&
          decoded['now_ms'] is int &&
          (decoded['now_ms'] as int) >= 0) {
        _authTimeout?.cancel();
        session = decoded['session'] as String;
        _remoteAtAuth = decoded['now_ms'] as int;
        _localAtAuth = _clock.elapsedMilliseconds;
        _sequence = 0;
        status = 'Authenticated';
      } else if (decoded['type'] == 'ack') {
        lastAck = decoded['reason']?.toString() ?? 'ack';
      } else if (decoded['type'] == 'error') {
        final reason =
            decoded['reason']?.toString() ??
            decoded['message']?.toString() ??
            decoded['error']?.toString() ??
            'Command rejected';
        if (reason == 'session_expired' ||
            reason == 'session_invalid' ||
            !authenticated) {
          _fail(reason);
          return;
        }
        _record(reason);
      }
      onMessage?.call(decoded);
      notifyListeners();
    } on FormatException {
      _record('Malformed telemetry');
    }
  }

  bool send(String type, [Map<String, dynamic> payload = const {}]) {
    if (!authenticated || _channel == null || _disposed) return false;
    final packet = <String, dynamic>{
      ...payload,
      'v': 1,
      'type': type,
      'session': session,
      'seq': ++_sequence,
      'sent_ms': remoteNow,
    };
    try {
      _channel!.sink.add(jsonEncode(packet));
      return true;
    } catch (_) {
      _fail('Send failed');
      return false;
    }
  }

  void _record(String text) {
    errors.insert(
      0,
      '${DateTime.now().toIso8601String().substring(11, 19)} $text',
    );
    if (errors.length > 30) errors.removeLast();
  }

  void _fail(String reason) {
    ++_generation;
    _authTimeout?.cancel();
    session = null;
    status = reason;
    _subscription?.cancel();
    _subscription = null;
    _channel?.sink.close();
    _channel = null;
    _record(reason);
    notifyListeners();
  }

  Future<void> disconnect() async {
    ++_generation;
    _authTimeout?.cancel();
    session = null;
    status = 'Disconnected';
    final previousSubscription = _subscription, previousChannel = _channel;
    _subscription = null;
    _channel = null;
    await previousSubscription?.cancel();
    await previousChannel?.sink.close();
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _authTimeout?.cancel();
    _subscription?.cancel();
    _channel?.sink.close();
    super.dispose();
  }
}
