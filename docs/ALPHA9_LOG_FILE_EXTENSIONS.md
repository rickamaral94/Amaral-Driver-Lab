# Alpha9 — emulator log file extensions

The emulator-log workflow now explicitly accepts `.txt` and `.log` files.

- Android's document picker remains compatible with providers that expose log files as plain text, log-specific MIME types or generic binary streams.
- The selected display name is validated case-insensitively before the file is read.
- Unsupported extensions receive a clear localized error.
- Unit tests cover `.txt`, `.log`, uppercase extensions and rejected formats.
