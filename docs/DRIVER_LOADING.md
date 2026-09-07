# Rootless driver loading, and what is still unverified

## How it works

Android's Vulkan loader will not pick up an ICD from app storage. `libadrenotools`
gets around that without root by installing a linker-namespace hook, so
`adrenotools_open_libvulkan` returns a handle to the Android loader configured to
load the ICD from a directory the app owns. Everything above that point in this
module resolves its entry points from that handle's `vkGetInstanceProcAddr`, which
is why `:core-vk` links no Vulkan library at all — linking the system loader in is
how the app would end up measuring whatever Android shipped while reporting the
package the user selected.

## Vendoring

`libadrenotools` is a submodule at `third_party/libadrenotools`, pinned to
`8fae8ce254dfc1344527e05301e43f37dea2df80` — the commit the previous generation of
this app shipped against, known to work on the Odin2 Portal. Pin it; do not track a
branch. When the commit changes, the change belongs in the same commit as a note in
the CHANGELOG, because the loader is part of what a published result depends on.

```
git submodule update --init --recursive
```

The build enables rootless loading when it finds the submodule's `CMakeLists.txt`,
and compiles without it otherwise.

## The three arguments that have to be right

`adrenotools_open_libvulkan` takes eight arguments and three of them will fail
silently if they are wrong. The library's own header is explicit about this, and the
first version of this integration got one of them wrong in exactly the way the header
warns about.

- **`hookLibDir` must be `applicationInfo.nativeLibraryDir`.** It is not scratch
  space. The library creates a linker namespace over this path and `dlopen`s
  `libhook_impl.so` and `libmain_hook.so` out of it, so it has to be where the APK's
  native libraries were actually extracted. Pass anything else and the call still
  returns a valid pointer — and then the hook fails, the system driver answers
  instead, and every subsequent number is filed under the wrong driver. That is the
  precise failure P1 exists to prevent, so the app also refuses an empty value before
  calling, and `IdentityGuard` catches it a second time afterwards.
- **`customDriverDir` must be on internal storage.** `DriverImporter` unpacks into
  `filesDir`, which satisfies this. `dlopen` refuses a library that any other app
  could have tampered with, so a package unpacked to shared storage would fail.
- **`useLegacyPackaging = true` is required**, in `:app` and `:core-vk`. Without it
  Android reads `.so` files straight out of the APK instead of extracting them, and
  `nativeLibraryDir` does not contain the hooks.

`tmpLibDir` is passed as null: it is only consulted below API 29 and this app's
minSdk is 30, where the library uses memfd instead.

## What the build now proves

- `libadrenotools` and `liblinkernsbypass` compile for arm64 against NDK 27.2 and
  link into `libamaral_vk.so`.
- The four hook libraries — `libmain_hook.so`, `libhook_impl.so`,
  `libfile_redirect_hook.so`, `libgsl_alloc_hook.so` — are built and packaged into
  the APK, so they land in `nativeLibraryDir` where the hook looks for them.
- The argument order in `icd_loader.cpp` matches the vendored header.

## Assumptions that still need a device

Compiling and packaging is not loading. None of this has been confirmed on hardware.

1. **The hook works on Android 13 at minSdk 30.** The linker-namespace behaviour
   `libadrenotools` depends on is not a public API and has changed shape between
   releases. The Odin2 Portal reports Android 13 (API 33).
2. **The ICD actually answers.** The header notes a failure mode where the hook loads
   but `vkEnumeratePhysicalDevices` returns zero devices. The app treats that as
   `NO_PHYSICAL_DEVICE` and refuses to run rather than falling back.
3. **`closeIcd` deliberately does not `dlclose` an adrenotools handle**, on the
   reasoning that the handle is the Android loader itself and unloading it would pull
   Vulkan out from under the process. The runner process is discarded per phase, so
   this leaks nothing that outlives the phase — but the reasoning is untested.
4. **`timestampValidBits` on Adreno 740 under Turnip.** The workload masks timestamps
   to that width and treats a decreasing value as a counter wrap. If the driver
   reports 64 valid bits but the counter is narrower, frametimes will show occasional
   enormous values. Check the first captured series for outliers before trusting it.
5. **`VK_FORMAT_D32_SFLOAT_S8_UINT` availability.** Workload 2 asks for it and falls
   back to `D24_UNORM_S8_UINT` and then `D32_SFLOAT`. The format that ran is recorded,
   because a run using a different depth format is not comparable with one that did not.
6. **4x MSAA plus an input attachment inside one render pass on this driver.** The
   sample count is negotiated down if the device cannot do 4x, and the number that ran
   is recorded rather than assumed.

The first two are what a single run on an Odin2 Portal would settle.
