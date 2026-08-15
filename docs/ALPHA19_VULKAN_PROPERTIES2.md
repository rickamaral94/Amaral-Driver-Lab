# Alpha19 — Vulkan properties2 negotiation

The alpha18 diagnostic trace proved that the offscreen comparison phases completed and that the
first visible scene crashed before Android window acquisition. The last durable native checkpoint
was `enumerate_device_extensions_list`; the only Vulkan dispatch between that checkpoint and
`acquire_android_window` was `vkGetPhysicalDeviceProperties2`.

The visual renderer created its instance with Vulkan 1.0 while loading and invoking the core
Vulkan 1.1 `vkGetPhysicalDeviceProperties2` command whenever the physical device advertised Vulkan
1.2 or newer. A physical device API version does not make a command legal for an instance created
against Vulkan 1.0. On the Odin 2 Portal Android loader/custom-driver path, the loader returned a
trampoline that crashed with a null dispatch instead of rejecting the invalid call.

Alpha19 now:

- queries `vkEnumerateInstanceVersion` and creates the visual instance as Vulkan 1.1 when supported;
- enables `VK_KHR_get_physical_device_properties2` when limited to a Vulkan 1.0 loader;
- calls `vkGetPhysicalDeviceProperties2` only when the instance negotiated the core command or its
  KHR extension;
- persists before/after checkpoints around the optional driver-properties query.

This change preserves the alpha18 task/process isolation and its synthetic crash result. It fixes
the newly isolated pre-surface dispatch fault rather than hiding it or continuing after a failed
calibration.
