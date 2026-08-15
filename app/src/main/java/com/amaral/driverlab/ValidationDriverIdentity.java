package com.amaral.driverlab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reconciles requested packages with Vulkan properties observed by disposable runner processes. */
final class ValidationDriverIdentity {
    static final String LIMITATION =
            "runtime_confirmed prova somente a família do driver. Sem o SHA-256 do DSO "
                    + "efetivamente carregado, não distingue dois builds Turnip que exponham as "
                    + "mesmas propriedades Vulkan e não prova que o build solicitado foi usado.";
    static final String CRITERION_DOC =
            "https://github.com/rickamaral94/Amaral-Driver-Lab/blob/main/docs/RESULT_SCHEMA.md";

    private ValidationDriverIdentity() {}

    static JSONObject auditPhases(JSONArray phases, JSONObject candidate,
                                  JSONObject reference) throws Exception {
        JSONArray observations = new JSONArray();
        List<Observation> candidateObservations = new ArrayList<>();
        List<Observation> referenceObservations = new ArrayList<>();
        int expectedCandidate = 0;
        int expectedReference = 0;
        for (int index = 0; index < phases.length(); ++index) {
            JSONObject phase = phases.optJSONObject(index);
            if (phase == null) continue;
            boolean candidateArm = DriverExecutionIdentity.isCandidateArm(phase);
            if (candidateArm) expectedCandidate++; else expectedReference++;
            Observation observation = observation(phase, index, candidateArm);
            phase.put("runtime_driver_identity", observation.runtimeJson());
            observations.put(observation.toJson());
            if (candidateArm) candidateObservations.add(observation);
            else referenceObservations.add(observation);
        }

        Arm candidateArm = resolveArm("candidate", candidateObservations,
                expectedCandidate, candidate);
        Arm referenceArm = resolveArm(reference == null ? "system" : "reference",
                referenceObservations, expectedReference, reference);
        String confidence = aggregateConfidence(candidateArm.confidence, referenceArm.confidence);
        String identity = candidateArm.identity;
        Object loaderStatus = loaderIsolation(candidateArm, referenceArm, candidate, reference);
        int expected = expectedCandidate + expectedReference;
        int observed = candidateArm.observed + referenceArm.observed;
        return new JSONObject()
                .put("driver_identity_policy_version", DriverIdentityPolicy.POLICY_VERSION)
                .put("scope", "suite")
                .put("driver", identity)
                .put("driver_identity_confidence", confidence)
                .put("eligible_for_aggregation",
                        DriverIdentityPolicy.RUNTIME_CONFIRMED.equals(confidence))
                .put("candidate", candidateArm.toJson())
                .put("reference", referenceArm.toJson())
                .put("runtime_observations", observations)
                .put("identity_observation_coverage", coverage(expected, observed))
                .put("loader_isolation_verified", loaderStatus)
                .put("effective_loaded_library_sha256", JSONObject.NULL)
                .put("build_identity_status", "not_observed")
                .put("limitations", LIMITATION);
    }

    static JSONObject aggregateQualification(JSONArray completedSteps) throws Exception {
        JSONArray sources = new JSONArray();
        int expected = 0;
        int observed = 0;
        int unaudited = 0;
        boolean disputed = false;
        boolean inferred = false;
        boolean loaderFalse = false;
        boolean loaderUnknown = false;
        Set<String> candidateIdentities = new LinkedHashSet<>();
        Set<String> referenceIdentities = new LinkedHashSet<>();
        for (int index = 0; index < completedSteps.length(); ++index) {
            JSONObject step = completedSteps.optJSONObject(index);
            if (step == null) continue;
            int declaredExpected = step.optInt("expected_identity_process_count", 0);
            expected += declaredExpected;
            if (!"completed".equals(step.optString("status"))) {
                inferred = true;
                sources.put(new JSONObject()
                        .put("step_id", step.optString("step_id"))
                        .put("driver_identity_confidence", DriverIdentityPolicy.INFERRED)
                        .put("reason", "step_without_completed_result"));
                continue;
            }
            JSONObject report = step.optJSONObject("report");
            JSONObject audit = report == null ? null : report.optJSONObject("driver_identity_audit");
            JSONObject source = new JSONObject().put("step_id", step.optString("step_id"));
            if (audit == null) {
                boolean legacy = report != null && report.optInt("schema_version", 1) < 14;
                if (legacy) unaudited++; else inferred = true;
                source.put("driver_identity_confidence", legacy
                                ? DriverIdentityPolicy.UNAUDITED : DriverIdentityPolicy.INFERRED)
                        .put("reason", "result_without_driver_identity_audit");
                sources.put(source);
                continue;
            }
            JSONObject stepCoverage = audit.optJSONObject("identity_observation_coverage");
            if (stepCoverage != null) {
                if (declaredExpected == 0) {
                    expected += stepCoverage.optInt("expected_process_count", 0);
                }
                observed += stepCoverage.optInt("observed_process_count", 0);
            }
            String confidence = audit.optString("driver_identity_confidence",
                    DriverIdentityPolicy.INFERRED);
            if (DriverIdentityPolicy.DISPUTED.equals(confidence)) disputed = true;
            else if (!DriverIdentityPolicy.RUNTIME_CONFIRMED.equals(confidence)) inferred = true;
            addIdentity(candidateIdentities, audit.optJSONObject("candidate"));
            addIdentity(referenceIdentities, audit.optJSONObject("reference"));
            Object loader = audit.opt("loader_isolation_verified");
            if (Boolean.FALSE.equals(loader) || "false".equals(loader)) loaderFalse = true;
            else if (!Boolean.TRUE.equals(loader) && !"true".equals(loader)) loaderUnknown = true;
            source.put("driver_identity_confidence", confidence)
                    .put("identity_observation_coverage",
                            stepCoverage == null ? JSONObject.NULL : stepCoverage)
                    .put("loader_isolation_verified", loader == null ? JSONObject.NULL : loader);
            sources.put(source);
        }
        if (candidateIdentities.size() > 1 || referenceIdentities.size() > 1) disputed = true;
        String confidence = disputed ? DriverIdentityPolicy.DISPUTED
                : unaudited > 0 ? DriverIdentityPolicy.UNAUDITED
                : inferred ? DriverIdentityPolicy.INFERRED
                : sources.length() > 0 ? DriverIdentityPolicy.RUNTIME_CONFIRMED
                : DriverIdentityPolicy.INFERRED;
        Object loader = loaderFalse ? false
                : loaderUnknown || sources.length() == 0 ? "not_determinable" : true;
        return new JSONObject()
                .put("driver_identity_policy_version", DriverIdentityPolicy.POLICY_VERSION)
                .put("scope", "qualification")
                .put("driver_identity_confidence", confidence)
                .put("eligible_for_aggregation",
                        DriverIdentityPolicy.RUNTIME_CONFIRMED.equals(confidence))
                .put("candidate_driver_families", array(candidateIdentities))
                .put("reference_driver_families", array(referenceIdentities))
                .put("identity_observation_coverage", coverage(expected, observed))
                .put("unaudited_result_count", unaudited)
                .put("loader_isolation_verified", loader)
                .put("sources", sources)
                .put("limitations", LIMITATION);
    }

    static JSONObject normalizeForRead(JSONObject report) throws Exception {
        JSONObject audit = report == null ? null : report.optJSONObject("driver_identity_audit");
        if (audit != null) return audit;
        return new JSONObject()
                .put("driver", DriverIdentityPolicy.UNKNOWN)
                .put("driver_identity_confidence",
                        report != null && report.optInt("schema_version", 1) < 14
                                ? DriverIdentityPolicy.UNAUDITED : DriverIdentityPolicy.INFERRED)
                .put("eligible_for_aggregation", false)
                .put("identity_observation_coverage", coverage(0, 0))
                .put("loader_isolation_verified", "not_determinable")
                .put("limitations", LIMITATION);
    }

    static JSONObject runtimeIdentity(JSONObject nativeResult) throws Exception {
        JSONObject capabilities = nativeResult == null ? null
                : nativeResult.optJSONObject("capabilities");
        String identity = identityFromCapabilities(capabilities);
        boolean observed = capabilities != null && hasRuntimeIdentityFields(capabilities);
        return runtimeJson(capabilities, identity, observed);
    }

    static boolean isEligible(JSONObject report) {
        if (report == null) return false;
        JSONObject audit = report.optJSONObject("driver_identity_audit");
        return audit != null && audit.optBoolean("eligible_for_aggregation", false)
                && DriverIdentityPolicy.RUNTIME_CONFIRMED.equals(
                        audit.optString("driver_identity_confidence"));
    }

    private static Observation observation(JSONObject phase, int index,
                                           boolean candidateArm) throws Exception {
        JSONObject nativeResult = phase.optJSONObject("native");
        JSONObject capabilities = nativeResult == null ? null
                : nativeResult.optJSONObject("capabilities");
        String identity = identityFromCapabilities(capabilities);
        boolean observed = capabilities != null && hasRuntimeIdentityFields(capabilities);
        String path = "phases[" + index + "].native.capabilities";
        return new Observation(index, phase.optInt("round", 0),
                phase.optString("driver_role", candidateArm ? "candidate" : "reference"),
                phase.optString("driver_sha256", ""),
                phase.optString("requested_library_sha256", ""),
                path, identity, observed, capabilities);
    }

    private static String identityFromCapabilities(JSONObject capabilities) {
        if (capabilities == null) return DriverIdentityPolicy.UNKNOWN;
        StringBuilder evidence = new StringBuilder();
        append(evidence, capabilities.optString("driver_id_name"));
        append(evidence, capabilities.optString("driver_name"));
        append(evidence, capabilities.optString("driver_info"));
        String identity = DriverIdentityPolicy.classifyRuntimeText(evidence.toString());
        return DriverIdentityPolicy.OTHER.equals(identity)
                ? DriverIdentityPolicy.OTHER : identity;
    }

    private static boolean hasRuntimeIdentityFields(JSONObject capabilities) {
        return capabilities.has("driver_id") || capabilities.has("driver_id_name")
                || capabilities.has("driver_name") || capabilities.has("driver_info");
    }

    private static JSONObject runtimeJson(JSONObject capabilities, String identity,
                                          boolean observed) throws Exception {
        return new JSONObject()
                .put("driver", identity)
                .put("driver_identity_confidence", observed
                        && !DriverIdentityPolicy.UNKNOWN.equals(identity)
                        ? DriverIdentityPolicy.RUNTIME_CONFIRMED
                        : DriverIdentityPolicy.INFERRED)
                .put("driver_identity_policy_version", DriverIdentityPolicy.POLICY_VERSION)
                .put("driver_id", value(capabilities, "driver_id"))
                .put("driver_id_name", value(capabilities, "driver_id_name"))
                .put("driver_name", value(capabilities, "driver_name"))
                .put("driver_info", value(capabilities, "driver_info"))
                .put("api_version", value(capabilities, "api_version"))
                .put("api_version_raw", value(capabilities, "api_version_raw"))
                .put("driver_version_raw", value(capabilities, "driver_version_raw"))
                .put("driver_version_decoded", value(capabilities,
                        "driver_version_decoded"))
                .put("conformance_version", value(capabilities,
                        "conformance_version"))
                .put("effective_loaded_library_sha256", JSONObject.NULL);
    }

    private static Arm resolveArm(String role, List<Observation> observations,
                                  int expected, JSONObject requestedDriver) throws Exception {
        int observed = 0;
        boolean hardQualcomm = false;
        Set<String> identities = new LinkedHashSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        JSONArray evidence = new JSONArray();
        Set<String> requestedLibraryHashes = new LinkedHashSet<>();
        for (Observation observation : observations) {
            if (!observation.observed) continue;
            observed++;
            if (!DriverIdentityPolicy.UNKNOWN.equals(observation.identity)) {
                identities.add(observation.identity);
                if (DriverIdentityPolicy.QUALCOMM_BLOB.equals(observation.identity)) {
                    hardQualcomm = true;
                }
            }
            fingerprints.add(observation.fingerprint());
            if (!observation.requestedLibrarySha256.isEmpty()) {
                requestedLibraryHashes.add(observation.requestedLibrarySha256);
            }
            evidence.put(new JSONObject()
                    .put("source", "vulkan_runtime")
                    .put("json_path", observation.path)
                    .put("identity", observation.identity)
                    .put("driver_id", value(observation.capabilities, "driver_id"))
                    .put("driver_id_name", value(observation.capabilities, "driver_id_name"))
                    .put("driver_name", value(observation.capabilities, "driver_name"))
                    .put("driver_info", value(observation.capabilities, "driver_info")));
        }
        String identity = hardQualcomm ? DriverIdentityPolicy.QUALCOMM_BLOB
                : identities.size() == 1 ? identities.iterator().next()
                : DriverIdentityPolicy.UNKNOWN;
        String requestedFamily = DriverIdentityPolicy.classifyPackageMetadata(requestedDriver);
        boolean divergence = identities.size() > 1 || fingerprints.size() > 1;
        boolean packageConflict = !DriverIdentityPolicy.UNKNOWN.equals(requestedFamily)
                && !DriverIdentityPolicy.UNKNOWN.equals(identity)
                && !requestedFamily.equals(identity);
        String confidence;
        if (divergence || packageConflict) confidence = DriverIdentityPolicy.DISPUTED;
        else if (observed == expected && expected > 0
                && !DriverIdentityPolicy.UNKNOWN.equals(identity)) {
            confidence = DriverIdentityPolicy.RUNTIME_CONFIRMED;
        } else confidence = DriverIdentityPolicy.INFERRED;
        if (!DriverIdentityPolicy.UNKNOWN.equals(requestedFamily)) {
            evidence.put(new JSONObject()
                    .put("source", "package_metadata_untrusted")
                    .put("json_path", role.equals("candidate") ? "candidate" : "reference")
                    .put("identity", requestedFamily));
        }
        return new Arm(role, identity, confidence, expected, observed, requestedFamily,
                requestedDriver == null ? "system"
                        : requestedDriver.optString("sha256", "unknown"),
                requestedLibraryHashes, fingerprints, evidence);
    }

    private static Object loaderIsolation(Arm candidate, Arm reference,
                                          JSONObject candidateDriver,
                                          JSONObject referenceDriver) {
        String candidatePackage = candidateDriver == null ? "system"
                : candidateDriver.optString("sha256", "unknown");
        String referencePackage = referenceDriver == null ? "system"
                : referenceDriver.optString("sha256", "unknown");
        if (candidatePackage.equalsIgnoreCase(referencePackage)) return "not_determinable";
        if (!DriverIdentityPolicy.UNKNOWN.equals(candidate.identity)
                && !DriverIdentityPolicy.UNKNOWN.equals(reference.identity)
                && !candidate.identity.equals(reference.identity)) return true;
        if (candidate.identity.equals(reference.identity)
                && !candidate.fingerprints.isEmpty() && !reference.fingerprints.isEmpty()
                && !candidate.fingerprints.equals(reference.fingerprints)) return true;
        // The pinned libadrenotools API does not expose the effective custom DSO path/hash.
        // Identical family properties therefore cannot prove contamination between two builds.
        return "not_determinable";
    }

    private static String aggregateConfidence(String left, String right) {
        if (DriverIdentityPolicy.DISPUTED.equals(left)
                || DriverIdentityPolicy.DISPUTED.equals(right)) {
            return DriverIdentityPolicy.DISPUTED;
        }
        if (DriverIdentityPolicy.UNAUDITED.equals(left)
                || DriverIdentityPolicy.UNAUDITED.equals(right)) {
            return DriverIdentityPolicy.UNAUDITED;
        }
        if (DriverIdentityPolicy.RUNTIME_CONFIRMED.equals(left)
                && DriverIdentityPolicy.RUNTIME_CONFIRMED.equals(right)) {
            return DriverIdentityPolicy.RUNTIME_CONFIRMED;
        }
        return DriverIdentityPolicy.INFERRED;
    }

    private static JSONObject coverage(int expected, int observed) throws Exception {
        return new JSONObject()
                .put("expected_process_count", expected)
                .put("observed_process_count", observed)
                .put("missing_process_count", Math.max(0, expected - observed))
                .put("coverage_percent", expected == 0 ? JSONObject.NULL
                        : observed * 100.0 / expected)
                .put("coverage_class", expected == 0 ? "none"
                        : observed == expected ? "full" : observed == 0 ? "none" : "partial");
    }

    private static void addIdentity(Set<String> output, JSONObject arm) {
        if (arm == null) return;
        String identity = arm.optString("driver", DriverIdentityPolicy.UNKNOWN);
        if (!DriverIdentityPolicy.UNKNOWN.equals(identity)) output.add(identity);
    }

    private static JSONArray array(Set<String> values) {
        JSONArray output = new JSONArray();
        for (String value : values) output.put(value);
        return output;
    }

    private static Object value(JSONObject source, String key) {
        return source != null && source.has(key) ? source.opt(key) : JSONObject.NULL;
    }

    private static void append(StringBuilder output, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (output.length() > 0) output.append(' ');
        output.append(value);
    }

    private static final class Observation {
        final int index;
        final int round;
        final String role;
        final String requestedPackageSha256;
        final String requestedLibrarySha256;
        final String path;
        final String identity;
        final boolean observed;
        final JSONObject capabilities;

        Observation(int index, int round, String role, String requestedPackageSha256,
                    String requestedLibrarySha256, String path, String identity,
                    boolean observed, JSONObject capabilities) {
            this.index = index;
            this.round = round;
            this.role = role;
            this.requestedPackageSha256 = requestedPackageSha256;
            this.requestedLibrarySha256 = requestedLibrarySha256;
            this.path = path;
            this.identity = identity;
            this.observed = observed;
            this.capabilities = capabilities;
        }

        JSONObject runtimeJson() throws Exception {
            return ValidationDriverIdentity.runtimeJson(capabilities, identity, observed);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("phase_index", index)
                    .put("round", round)
                    .put("role", role)
                    .put("requested_package_sha256", requestedPackageSha256.isEmpty()
                            ? JSONObject.NULL : requestedPackageSha256)
                    .put("requested_library_sha256", requestedLibrarySha256.isEmpty()
                            ? JSONObject.NULL : requestedLibrarySha256)
                    .put("json_path", path)
                    .put("observed", observed)
                    .put("runtime_driver_identity", runtimeJson());
        }

        String fingerprint() throws Exception {
            if (!observed) return "missing";
            JSONObject value = runtimeJson();
            // driverVersion raw is vendor-specific and only appears inside a family-qualified key.
            return identity + ":" + JsonCanonicalizer.sha256(value);
        }
    }

    private static final class Arm {
        final String role;
        final String identity;
        final String confidence;
        final int expected;
        final int observed;
        final String requestedFamily;
        final String requestedPackageSha256;
        final Set<String> requestedLibraryHashes;
        final Set<String> fingerprints;
        final JSONArray evidence;

        Arm(String role, String identity, String confidence, int expected, int observed,
            String requestedFamily, String requestedPackageSha256,
            Set<String> requestedLibraryHashes, Set<String> fingerprints,
            JSONArray evidence) {
            this.role = role;
            this.identity = identity;
            this.confidence = confidence;
            this.expected = expected;
            this.observed = observed;
            this.requestedFamily = requestedFamily;
            this.requestedPackageSha256 = requestedPackageSha256;
            this.requestedLibraryHashes = requestedLibraryHashes;
            this.fingerprints = fingerprints;
            this.evidence = evidence;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("role", role)
                    .put("driver", identity)
                    .put("driver_display_name", DriverIdentityPolicy.displayName(identity))
                    .put("driver_identity_confidence", confidence)
                    .put("requested_package_family", requestedFamily)
                    .put("requested_package_sha256", requestedPackageSha256)
                    .put("requested_library_sha256", array(requestedLibraryHashes))
                    .put("effective_loaded_library_sha256", JSONObject.NULL)
                    .put("identity_observation_coverage", coverage(expected, observed))
                    .put("driver_identity_evidence", evidence);
        }
    }
}
