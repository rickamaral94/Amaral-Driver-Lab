# Privacy and security

## What leaves the device

Nothing, unless you press **Publish to GitHub** or **Export**. There is no
automatic telemetry and no crash reporting.

Before anything is sent, the app shows the exact payload. What it contains:

- the device model, SoC, Android version and build fingerprint;
- the driver identity Vulkan reported, and the SHA-256 of the `.so` that ran;
- the raw frametime series, the image hashes, and the failures;
- thermal and battery readings taken during the run;
- an optional nickname you typed.

## What is never collected

No IMEI, no `ANDROID_ID`, no advertising identifier, no account, no location, no
installed-app list. The build fingerprint identifies a *model and firmware build*,
not a handset, and it is there because a result from a different firmware is not
comparable with one from yours.

The nickname is optional, free text, and yours to choose. If you want a result to be
anonymous, leave it empty.

## Network access

`INTERNET` is used for exactly two things:

1. publishing a result you explicitly asked to publish;
2. refreshing the allowlist that maps known driver hashes to friendly names.

There is no third use, and the second one sends nothing about you.

## The GitHub token

Publishing uses the OAuth **Device Flow**: the app shows a code, you enter it in a
browser, and the app receives a token. No client secret ships in the APK, because an
APK cannot keep one.

The token is stored in `EncryptedSharedPreferences`, is never written to a log, and
can be cleared from the app. Clearing it removes the local copy; revoking it on
GitHub is a separate step and the app says so, because only you can do that.

Backups are disabled for the whole app, so the token cannot leave the device through
a cloud backup or a device transfer.

## Running untrusted native code

Importing a driver package means running native code from a zip you chose. The app
checks what it can before loading — the archive cannot write outside its own
directory, cannot expand without limit, and must contain an aarch64 shared object —
but once the ICD is loaded it runs with the app's privileges.

Use packages you built or whose hashes you trust. The SHA-256 the app shows is of
the library it will actually load, so you can compare it against a published one.

The benchmark runs in a separate process, which limits the damage a misbehaving
driver can do to the app, and is not a security boundary against a hostile one.
