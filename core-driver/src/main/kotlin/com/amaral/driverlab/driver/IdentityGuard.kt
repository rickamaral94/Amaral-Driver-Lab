package com.amaral.driverlab.driver

/**
 * P1, enforced. Nothing is measured until the app can prove which driver is about to run.
 *
 * The failure this exists for: a Turnip package that does not load, a silent fall back to the
 * Qualcomm blob, and a report full of real numbers under the wrong label. That has happened, and
 * a report like that is worse than no report, because it looks like evidence.
 */
public sealed interface IdentityVerdict {

    /** Cleared to run. [label] is what the result will be filed under. */
    public data class Accepted(
        val identity: DriverIdentity,
        val label: String,
        /**
         * True when the library's hash is not in the allowlist. The run proceeds and is published,
         * marked `unverified`: an unknown build is a normal thing to test, it just cannot be
         * described as a known one.
         */
        val unverified: Boolean,
    ) : IdentityVerdict

    /** Refused. [reason] is written for the user, not for a log. */
    public data class Rejected(
        val code: RejectionCode,
        val reason: String,
        val identity: DriverIdentity?,
    ) : IdentityVerdict

    public enum class RejectionCode {
        /** The loader reported success but Vulkan never came up, so nothing can be attributed. */
        NO_IDENTITY,

        /**
         * The user asked for an imported package and the system's proprietary driver answered.
         * The ICD did not take.
         */
        FELL_BACK_TO_SYSTEM_DRIVER,

        /** A package was loaded, but the driver reporting back is not the one that was loaded. */
        UNEXPECTED_DRIVER_FAMILY,

        /** The device did not expose the identity fields at all. */
        MISSING_DRIVER_PROPERTIES,
    }
}

public class IdentityGuard(private val allowlist: DriverAllowlist = DriverAllowlist.empty()) {

    /**
     * @param requested what the user selected, before anything was loaded.
     * @param identity what Vulkan reported once the ICD was in the process; null if it never came up.
     */
    public fun check(requested: RequestedDriver, identity: DriverIdentity?): IdentityVerdict {
        if (identity == null) {
            return IdentityVerdict.Rejected(
                IdentityVerdict.RejectionCode.NO_IDENTITY,
                "The driver loaded but Vulkan did not report an identity, so this run could not be " +
                    "attributed to any driver. Nothing was measured.",
                null,
            )
        }

        // VK_KHR_driver_properties is core since Vulkan 1.2 and widely present before that, but a
        // device that does not expose it leaves us unable to say what ran — which is the one thing
        // this check exists to establish.
        if (identity.driverId == 0 && identity.driverName.isBlank()) {
            return IdentityVerdict.Rejected(
                IdentityVerdict.RejectionCode.MISSING_DRIVER_PROPERTIES,
                "This device does not report VkPhysicalDeviceDriverProperties, so the app cannot " +
                    "prove which driver executed. Benchmarks are disabled rather than mislabelled.",
                identity,
            )
        }

        when (requested) {
            is RequestedDriver.System -> {
                // Nothing to prove: whatever the system driver is, it is the system driver. It is
                // still marked non-rankable downstream.
                return IdentityVerdict.Accepted(
                    identity = identity,
                    label = "System driver · ${VkDriverId.describe(identity.driverId)}",
                    unverified = false,
                )
            }

            is RequestedDriver.Package -> {
                if (identity.isQualcommProprietary) {
                    return IdentityVerdict.Rejected(
                        IdentityVerdict.RejectionCode.FELL_BACK_TO_SYSTEM_DRIVER,
                        "You selected \"${requested.displayName}\", but Vulkan reports " +
                            "${VkDriverId.describe(identity.driverId)}. The package did not load and the " +
                            "system driver answered instead. Nothing was measured, because timing the " +
                            "system driver under a Turnip label is how bad reports get made.",
                        identity,
                    )
                }
                if (requested.expectMesa && !identity.isMesa) {
                    return IdentityVerdict.Rejected(
                        IdentityVerdict.RejectionCode.UNEXPECTED_DRIVER_FAMILY,
                        "You selected \"${requested.displayName}\", expected a Mesa driver, and Vulkan " +
                            "reports ${VkDriverId.describe(identity.driverId)}. The run was stopped " +
                            "because the result could not be labelled honestly.",
                        identity,
                    )
                }

                val known = allowlist.lookup(requested.libraryChecksum)
                return IdentityVerdict.Accepted(
                    identity = identity,
                    label = known?.label ?: requested.displayName,
                    unverified = known == null,
                )
            }
        }
    }
}

/** What the user asked to run, captured before the loader touches anything. */
public sealed interface RequestedDriver {

    public data object System : RequestedDriver

    public data class Package(
        val libraryChecksum: String,
        val displayName: String,
        /**
         * Where the package was unpacked, and which file inside it is the ICD. The loader needs
         * both, so they travel with the request rather than being looked up again later — an
         * earlier version left them out of the request entirely, and the package could never
         * have loaded however well the rest of the chain worked.
         */
        val installDirectory: String = "",
        val libraryName: String = "",
        /**
         * Whether a Mesa driver is expected. True for anything imported as a Turnip build; the
         * check is on the driver family rather than on Turnip exactly, so a Mesa driver for a
         * different GPU still fails loudly instead of being labelled Turnip.
         */
        val expectMesa: Boolean = true,
    ) : RequestedDriver {
        /** False when this request could not possibly load, whatever the loader does. */
        public val loadable: Boolean
            get() = installDirectory.isNotBlank() && libraryName.isNotBlank()
    }

    public companion object {
        public fun of(driverPackage: DriverPackage, expectMesa: Boolean = true): Package = Package(
            libraryChecksum = driverPackage.libraryChecksum,
            displayName = driverPackage.displayName,
            installDirectory = driverPackage.installDirectory.absolutePath,
            libraryName = driverPackage.metadata.libraryName,
            expectMesa = expectMesa,
        )
    }
}
