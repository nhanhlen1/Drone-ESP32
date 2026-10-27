import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

class _Attempt {
  _Attempt(this.generation);
  final int generation;
  final cancelled = Completer<void>();
  WebSocketChannel? channel;
  StreamSubscription<dynamic>? subscription;
  Timer? timeout;
  Future<void>? cleanup;
}

/// One authenticated session. No automatic reconnect or restored ARM state.
/// Socket attempts own their resources even while a newer attempt tears them down.
class DroneLink extends ChangeNotifier {
  DroneLink({
    WebSocketChannel Function(Uri)? channelFactory,
    this.connectionTimeout = const Duration(seconds: 5),
    this.authenticationTimeout = const Duration(seconds: 5),
    this.teardownTimeout = const Duration(seconds: 1),
  }) : _channelFactory = channelFactory ?? WebSocketChannel.connect;

  final WebSocketChannel Function(Uri) _channelFactory;
  final Duration connectionTimeout, authenticationTimeout, teardownTimeout;
  _Attempt? _attempt;
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
  bool get authenticated => !_disposed && session != null;
  int get remoteNow =>
      (_remoteAtAuth + _clock.elapsedMilliseconds - _localAtAuth) & 0xffffffff;

  bool _current(_Attempt attempt) =>
      !_disposed &&
      identical(_attempt, attempt) &&
      attempt.generation == _generation;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> connect(String endpoint, String token) async {
    if (_disposed) return;
    final uri = Uri.tryParse(endpoint);
    if (uri == null ||
        !['ws', 'wss'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.query.isNotEmpty ||
        uri.fragment.isNotEmpty ||
        token.isEmpty) {
      throw const FormatException(
        'Enter a ws:// or wss:// endpoint and session token.',
      );
    }
    // Reserve ownership synchronously, BEFORE any await or listener notification.
    final previous = _attempt;
    final attempt = _Attempt(++_generation);
    _attempt = attempt;
    session = null;
    status = 'Connecting';
    _notify();
    await _teardown(previous);
    if (!_current(attempt)) return;
    try {
      final channel = _channelFactory(uri);
      attempt.channel = channel;
      // Both ready and the stream may report the same failed handshake. Attach
      // error handlers to both before awaiting either; stale errors are consumed.
      final ready = Future.any<void>([
        channel.ready,
        attempt.cancelled.future,
      ]).then((_) => true, onError: (Object _) => false);
      final subscription = channel.stream.listen(
        (data) {
          if (_current(attempt)) _receive(attempt, data);
        },
        onError: (Object error) {
          if (_current(attempt)) _fail(attempt, 'Connection error');
        },
        onDone: () {
          if (_current(attempt)) _fail(attempt, 'Connection closed');
        },
      );
      if (!_current(attempt)) {
        unawaited(_finishCleanup(subscription.cancel));
        return;
      }
      attempt.subscription = subscription;
      attempt.timeout = Timer(connectionTimeout, () {
        if (_current(attempt)) _fail(attempt, 'Connection timed out');
      });
      final connected = await ready;
      if (!_current(attempt)) return;
      attempt.timeout?.cancel();
      if (!connected) {
        _fail(attempt, 'Connection failed');
        return;
      }
      status = 'Authenticating';
      attempt.timeout = Timer(authenticationTimeout, () {
        if (_current(attempt) && !authenticated) {
          _fail(attempt, 'Authentication timed out');
        }
      });
      channel.sink.add(jsonEncode({'v': 1, 'type': 'auth', 'token': token}));
      _notify();
    } catch (_) {
      // Runtime transport failures are represented by status. Superseded/disposed
      // attempts never throw late errors into an unrelated screen or new session.
      if (_current(attempt)) _fail(attempt, 'Connection failed');
    }
  }

  void _receive(_Attempt attempt, dynamic data) {
    try {
      if (data is! String || data.length > 16384) return;
      final decoded = jsonDecode(data);
      if (decoded is! Map<String, dynamic> || decoded['v'] != 1) return;
      receivedPackets++;
      if ((decoded['type'] == 'auth' || decoded['type'] == 'auth_ok') &&
          decoded['session'] is String &&
          (decoded['session'] as String).isNotEmpty &&
          decoded['now_ms'] is int &&
          (decoded['now_ms'] as int) >= 0 &&
          (decoded['now_ms'] as int) <= 0xffffffff) {
        attempt.timeout?.cancel();
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
          _fail(attempt, reason);
          return;
        }
        _record(reason);
      }
      onMessage?.call(decoded);
      _notify();
    } on FormatException {
      _record('Malformed telemetry');
      _notify();
    }
  }

  bool send(String type, [Map<String, dynamic> payload = const {}]) {
    final attempt = _attempt;
    if (!authenticated || attempt?.channel == null || _disposed) return false;
    if (_sequence >= 0xffffffff) {
      _fail(attempt!, 'Sequence exhausted; reconnect while disarmed');
      return false;
    }
    final packet = <String, dynamic>{
      ...payload,
      'v': 1,
      'type': type,
      'session': session,
      'seq': ++_sequence,
      'sent_ms': remoteNow,
    };
    try {
      attempt!.channel!.sink.add(jsonEncode(packet));
      return true;
    } catch (_) {
      _fail(attempt!, 'Send failed');
      return false;
    }
  }

  void _record(String text) {
    if (_disposed) return;
    errors.insert(
      0,
      '${DateTime.now().toIso8601String().substring(11, 19)} $text',
    );
    if (errors.length > 30) errors.removeLast();
  }

  void _fail(_Attempt attempt, String reason) {
    if (!_current(attempt)) return;
    ++_generation;
    _attempt = null;
    session = null;
    status = reason;
    unawaited(_teardown(attempt));
    _record(reason);
    _notify();
  }

  // Cancellation and close are independent: a stuck cancellation must not prevent
  // socket close, and a stuck close must not block reconnect/dispose indefinitely.
  Future<void> _finishCleanup(Future<dynamic> Function() operation) async {
    try {
      await operation().timeout(teardownTimeout);
    } catch (_) {
      // Cleanup failures are consumed; ownership has already been revoked.
    }
  }

  Future<void> _teardown(_Attempt? attempt) {
    if (attempt == null) return Future<void>.value();
    if (attempt.cleanup != null) return attempt.cleanup!;
    attempt.timeout?.cancel();
    if (!attempt.cancelled.isCompleted) attempt.cancelled.complete();
    final subscription = attempt.subscription, channel = attempt.channel;
    attempt.subscription = null;
    attempt.channel = null;
    return attempt.cleanup = Future.wait<void>([
      if (subscription != null) _finishCleanup(subscription.cancel),
      if (channel != null) _finishCleanup(channel.sink.close),
    ]).then((_) {});
  }

  Future<void> disconnect() async {
    if (_disposed) return;
    ++_generation;
    final previous = _attempt;
    _attempt = null;
    session = null;
    status = 'Disconnected';
    _notify();
    await _teardown(previous);
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    ++_generation;
    final previous = _attempt;
    _attempt = null;
    session = null;
    onMessage = null;
    unawaited(_teardown(previous));
    _clock.stop();
    super.dispose();
  }
}
