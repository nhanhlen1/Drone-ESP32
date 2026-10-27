import 'package:flutter/material.dart';

class ConnectionDetails {
  final String endpoint;
  final String token;
  const ConnectionDetails(this.endpoint, this.token);
}

/// The route owns its inputs until its subtree finishes the exit transition.
/// A showDialog result completes at pop time, before these fields unmount.
class ConnectionDialog extends StatefulWidget {
  const ConnectionDialog({super.key});

  @override
  State<ConnectionDialog> createState() => _ConnectionDialogState();
}

class _ConnectionDialogState extends State<ConnectionDialog> {
  late final TextEditingController _address;
  late final TextEditingController _token;
  bool _closing = false;

  @override
  void initState() {
    super.initState();
    _address = TextEditingController(text: 'ws://192.168.4.1:81/ws');
    _token = TextEditingController();
  }

  void _close([ConnectionDetails? result]) {
    if (!mounted || _closing || ModalRoute.of(context)?.isCurrent != true) {
      return;
    }
    _closing = true;
    Navigator.of(context).pop(result);
  }

  @override
  void dispose() {
    _address.dispose();
    _token.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Connect to drone AP'),
    content: SizedBox(
      width: 390,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Join ESP32_DRONE in phone Wi-Fi settings first. Keep mobile data/VPN routes from taking over this connection.',
            ),
            const SizedBox(height: 18),
            TextField(
              key: const Key('connection-endpoint'),
              controller: _address,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: 'WebSocket endpoint',
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              key: const Key('connection-token'),
              controller: _token,
              obscureText: true,
              autocorrect: false,
              enableSuggestions: false,
              decoration: const InputDecoration(
                labelText: 'Session token (not saved)',
              ),
            ),
            const SizedBox(height: 12),
            const Text(
              'Local ws:// is unencrypted. Use an isolated WPA2 AP; this console does not make the aircraft flight-ready.',
              style: TextStyle(fontSize: 12, color: Colors.white54),
            ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(onPressed: _close, child: const Text('Cancel')),
      FilledButton(
        onPressed: () =>
            _close(ConnectionDetails(_address.text.trim(), _token.text)),
        child: const Text('Connect'),
      ),
    ],
  );
}
