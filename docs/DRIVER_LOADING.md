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

`libadrenotools` is not committed here. The build looks for it at
`third_party/libadrenotools` and enables rootless loading when it finds a
`CMakeLists.txt` there:

```
git submodule add https://github.com/bylaws/libadrenotools third_party/libadrenotools
git -C third_party/libadrenotools checkout 8fae8ce254dfc1344527e05301e43f37dea2df80
git -C third_party/libadrenotools submodule update --init --recursive
```

That commit is the one the previous generation of this app shipped against and is
known to work on the Odin2 Portal. Pin it; do not track a branch. When the commit
changes, the change belongs in the same commit as a note in the CHANGELOG, because
the loader is part of what a published result depends on.

## Without it

The build still succeeds and still runs, against the system driver only. An attempt
to load an imported package returns `HOOK_UNAVAILABLE` with a message saying the
package was not loaded **and that the system driver was not used in its place**.

That last clause is the whole design. Falling back to the system loader here is
exactly how a benchmark ends up timing the Qualcomm blob under a Turnip label, and
`IdentityGuard` would catch it a second time — but the loader must not create the
situation in the first place.

## Assumptions that need a device to confirm

None of the following has been checked on hardware. They are written down rather
than assumed away, per section 15 of the spec.

1. **The hook works on Android 11 through 15 at minSdk 30.** The linker namespace
   behaviour `libadrenotools` depends on is not a public API and has changed shape
   between releases. Verify on the Odin2 Portal first, then on the oldest supported
   Android version available.
2. **`adrenotools_open_libvulkan`'s argument order and semantics** as used in
   `icd_loader.cpp` — specifically that the driver directory and the hook directory
   both need a trailing separator, and that passing `nullptr` for the file-redirect
   and GPU-mapping arguments is accepted. Taken from the library's headers, not
   from a run.
3. **Closing the handle.** `closeIcd` deliberately does not `dlclose` an
   adrenotools handle, on the reasoning that the handle is the Android loader
   itself and unloading it would pull Vulkan out from under the process. The runner
   process is discarded per phase, so this leaks nothing that outlives the phase —
   but the reasoning is untested.
4. **`timestampValidBits` on Adreno 740 under Turnip.** The workload masks
   timestamps to that width and treats a decreasing value as a counter wrap. If the
   driver reports 64 valid bits but the counter is narrower, frametimes will show
   occasional enormous values. Check the first captured series for outliers before
   trusting any number from it.
5. **`VK_FORMAT_D32_SFLOAT_S8_UINT` availability.** Workload 2 asks for it and falls
   back to `D24_UNORM_S8_UINT` and then `D32_SFLOAT`. The format that ran is recorded,
   because a run using a different depth format is not comparable with one that did not.
6. **4x MSAA plus an input attachment inside one render pass on this driver.** The
   sample count is negotiated down if the device reports it cannot do 4x, and again,
   the number that ran is recorded rather than assumed.

Until 1 and 2 are confirmed on the Odin2 Portal, the app can load the system driver
and nothing else, and it says so rather than producing numbers.
