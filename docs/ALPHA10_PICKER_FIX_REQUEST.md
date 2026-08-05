# Alpha10 picker fix request

Real-device validation showed that some Android document providers disable `.log` files before selection when a MIME whitelist is supplied. This branch removes that provider-side whitelist while preserving the app-side `.txt` / `.log` extension validation.
