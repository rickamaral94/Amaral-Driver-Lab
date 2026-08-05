# Alpha10: unrestricted Android log picker

The Android document picker now opens with `*/*` and without `Intent.EXTRA_MIME_TYPES`.

Some Android file managers report `.log` files with unknown or vendor-specific MIME types and disable them when a MIME whitelist is supplied. The application still validates the selected display name locally and accepts only `.txt` and `.log`, case-insensitively.
