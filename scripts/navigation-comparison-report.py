#!/usr/bin/env python3
"""Audit the 2 engines x 4 workloads x 3 seeds matrix without hiding incomplete runs."""
import argparse
import hashlib
import json
import math
from pathlib import Path


# Frozen NavigationTuning.DEFAULT and its cache partitions. Do not accept a
# larger limit merely because the implementation reports that larger value.
ROOM_TRANSIENT_LIMIT = 65536
SERVER_TRANSIENT_LIMIT = 262144
CACHE_LIMITS = {
    "tiles": 512,
    "nodes": 65536,
    "edges": 196608,  # Three quarters of fineEdges; portals own the remainder.
    "failures": 4096,
    "geometryCells": 8192,
    "portalPaths": 65536,
    "portalEdgeReferences": 65536,
}
SEARCH_RECORD_LIMIT = 65536
PROCESS_FIELDS = ("java", "os", "processors", "maxHeapBytes")
DEBUG_PROPERTY = "-Dcodpattern.zombies.navigationDebug"
TARGET_MOTION_PROPERTY = "-Dcodpattern.zombies.navigationLoadTargetMotion"
TARGET_MOTION_POLICIES = ("after-deadline", "after-first-arrival")


def read(path):
    return json.loads(path.read_text()) if path.is_file() else None


def finite(value):
    return not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value)


def nonnegative_count(value):
    return type(value) is int and value >= 0


def inspect_launch_debug(launch, errors):
    """Diagnostic logging changes the workload; final comparisons require an explicit non-debug launch."""
    if not launch or launch.get("navigationDebugEnabled") is not False:
        errors.append("final load evidence requires explicit navigationDebugEnabled=false")
    arguments = None if not launch else launch.get("jvmArgs")
    if not isinstance(arguments, list) or any(not isinstance(arg, str) for arg in arguments):
        errors.append("actual JVM argument list missing or invalid; debug mode cannot be verified")
        return []
    for argument in arguments:
        if argument == DEBUG_PROPERTY or argument.startswith(DEBUG_PROPERTY + "="):
            # Boolean.getBoolean accepts every case spelling of true. Reject any
            # such occurrence, even if a later duplicate would override it; only
            # explicit false (or an absent property) is final evidence.
            if argument.partition("=")[2].lower() != "false":
                errors.append("navigation debug JVM property must be explicitly false when present")
    return arguments


def inspect_target_motion(launch, arguments, expected_policy, errors):
    if expected_policy not in TARGET_MOTION_POLICIES:
        errors.append("unknown requested target motion policy")
    if not launch or launch.get("targetMotionPolicy") != expected_policy:
        errors.append("launch target motion policy missing or differs from the requested workload")
    explicit = [argument.partition("=")[2] for argument in arguments
                if argument == TARGET_MOTION_PROPERTY or argument.startswith(TARGET_MOTION_PROPERTY + "=")]
    if any(value != expected_policy for value in explicit) \
            or (not explicit and expected_policy != "after-deadline"):
        errors.append("actual JVM target motion policy differs from the requested workload")


def inspect_room_motion(room, expected_policy, errors):
    if room.get("targetMotionPolicy") != expected_policy:
        errors.append("room target motion policy missing or differs from the requested workload")
    start = room.get("motionStartedTick")
    if not nonnegative_count(start) or start >= 6000:
        errors.append("actual player motion start missing or outside the observation")
    elif room.get("playerMotionSamples") != 6000 - start \
            or room.get("playerMotionSamplesBeforeDeadline") != max(0, 951 - start):
        errors.append("player motion samples do not cover every tick from its start through tick 5999")
    if not finite(room.get("maxPlayerStep")) or room["maxPlayerStep"] < 0:
        errors.append("actual maximum player step missing or invalid")
    if expected_policy == "after-first-arrival":
        first = room.get("firstArrivalTick")
        actual_arrivals = [entity.get("arrivalTick") for entity in room.get("entities", [])
                           if finite(entity.get("arrivalTick"))]
        if not nonnegative_count(first) or first > 950 or start != first \
                or room.get("motionPhaseOriginTick") != start \
                or not actual_arrivals or first != min(actual_arrivals):
            errors.append("reference motion must start on the actual first-arrival frame")
        if room.get("motionFirstStep") != 0 or isinstance(room.get("motionFirstStep"), bool) \
                or not finite(room.get("maxPlayerStep")) or not 0 < room["maxPlayerStep"] <= 0.06 \
                or room.get("playerMotionWithinPlatform") is not True:
            errors.append("reference player motion lacks continuous, bounded, in-platform evidence")
        comparison = "same-event-relative-policy-not-identical-absolute-tick-input"
    else:
        if start != 951 or room.get("motionPhaseOriginTick") != 950:
            errors.append("original pressure motion must retain the fixed tick > 950 schedule")
        comparison = "same-fixed-tick-input"
    if room.get("targetMotionInputComparison") != comparison:
        errors.append("target motion input comparison semantics missing or inconsistent")
    return {key: room.get(key) for key in ("roomIndex", "targetMotionPolicy", "firstArrivalTick",
            "motionStartedTick", "motionPhaseOriginTick", "maxPlayerStep", "motionFirstStep",
            "playerMotionSamples", "playerMotionSamplesBeforeDeadline", "playerMotionWithinPlatform",
            "targetMotionInputComparison")}


def inspect_capacity(room, entity_count, errors):
    """Audit observed snapshots; only TransientCapacity exposes a real peak."""
    cache_maxima, planning_maxima = {}, {}
    transient_peaks = {"room": 0, "server": 0}

    def bounded(data, limits, maxima, location):
        for key, limit in limits.items():
            value = data.get(key)
            if not nonnegative_count(value) or value > limit:
                errors.append(f"missing or exceeded sampled capacity: {location}.{key} (limit {limit})")
            if nonnegative_count(value):
                maxima[key] = max(maxima.get(key, 0), value)

    def transient(data, expected, scope, location):
        if data.get("transientLimit") != expected:
            errors.append(f"changed or missing frozen {scope} transient limit: {location}")
        current, peak = data.get("transientEntries"), data.get("peakTransientEntries")
        if not nonnegative_count(current) or not nonnegative_count(peak) or not 0 <= current <= peak <= expected:
            errors.append(f"missing or exceeded {scope} retained-memory accounting: {location}")
        if nonnegative_count(peak):
            transient_peaks[scope] = max(transient_peaks[scope], peak)

    def cache_snapshot(data, location):
        data = data or {}
        bounded(data, CACHE_LIMITS, cache_maxima, location)
        # Pinning may retain dependencies of an executing route after tile LRU
        # eviction; pinnedTiles is not constrained by residentTiles=512.
        pinned = data.get("pinnedTiles")
        if not nonnegative_count(pinned):
            errors.append(f"missing or invalid pinned tile observation: {location}")
        else:
            cache_maxima["pinnedTiles"] = max(cache_maxima.get("pinnedTiles", 0), pinned)
        transient(data, ROOM_TRANSIENT_LIMIT, "room", location)

    def planning_snapshot(data, location):
        bounded(data or {}, {"searchRecords": SEARCH_RECORD_LIMIT, "activeRequests": entity_count},
                planning_maxima, location)

    cache_snapshot(room.get("graphCache"), "final.graphCache")
    planning_snapshot(room.get("planning"), "final.planning")
    transient(room.get("globalSchedulerTiming") or {}, SERVER_TRANSIENT_LIMIT, "server", "final.scheduler")
    for phase in ("coldInitialPursuit", "retainedCacheSustainedPursuit"):
        for endpoint in ("cacheAtStart", "cacheAtEnd"):
            cache_snapshot((room.get(phase) or {}).get(endpoint), f"{phase}.{endpoint}")
    samples = room.get("runtimeSamplesEvery100Ticks") or []
    ticks = [sample.get("tick") for sample in samples]
    if ticks != list(range(100, 6001, 100)):
        errors.append("resident capacity observations must cover each 100-tick sample through tick 6000")
    for sample in samples:
        location = f"tick {sample.get('tick')}"
        cache_snapshot(sample.get("cache"), location + ".cache")
        planning_snapshot(sample.get("planning"), location + ".planning")
        transient(sample.get("globalSchedulerTiming") or {}, SERVER_TRANSIENT_LIMIT, "server", location + ".scheduler")
    return dict(roomIndex=room.get("roomIndex"), transientHighWaterMarks=transient_peaks,
                sampledCacheMaxima=cache_maxima, sampledPlanningMaxima=planning_maxima,
                scheduledSamples=len(samples),
                residentMeasurementScope="Final/phase snapshots and every 100 ticks; these are sampled maxima, not all-tick high-water marks.",
                unmeasured="No independent high-water mark for resident graph/search/pinned tiles or direct version-table count.")


def inspect(directory, engine, workload, seed, expected_policy="after-deadline"):
    errors = []
    capacity = []
    summary = read(directory / "run-summary.json")
    launch = read(directory / "launch-environment.json")
    jvm_arguments = inspect_launch_debug(launch, errors)
    inspect_target_motion(launch, jvm_arguments, expected_policy, errors)
    world = read(directory / "world-environment.json")
    profile = read(directory / "jfr-summary.json")
    paths = sorted(directory.glob("load-*-room-*.json"))
    rooms = [read(path) for path in paths]
    count, room_count = (32, 4) if workload == "multi" else (int(workload), 1)
    if not summary or not summary.get("success") or summary.get("executedTests") != 1:
        errors.append("missing, incomplete, or failing GameTest run")
    elif (summary.get("engine"), str(summary.get("scenario")), summary.get("seed")) != (engine, workload, seed):
        errors.append("run metadata does not match the requested matrix cell")
    if not world or not world.get("verified") or world.get("actualWorldSeed") != seed \
            or world.get("actualOverworldGenerator") != "net.minecraft.world.level.levelgen.FlatLevelSource" \
            or world.get("generateStructures") is not False:
        errors.append("actual fixed-seed flat test world not verified")
    if not world or not world.get("compiledClassesVerified"):
        errors.append("actual business class loader resources were not matched to the compiled manifest")
    if not world or world.get("actualWorldDifficulty") != "easy":
        errors.append("load matrix must use the frozen default easy difficulty for both engines")
    if len(rooms) != room_count or {room.get("roomIndex") for room in rooms} != set(range(room_count)):
        errors.append("missing or duplicated room reports")
    process = {key: rooms[0].get(key) for key in PROCESS_FIELDS} if rooms else None
    motion = []
    for room in rooms:
        motion.append(inspect_room_motion(room, expected_policy, errors))
        if not isinstance(room.get("java"), str) or not room["java"].strip() \
                or not isinstance(room.get("os"), str) or not room["os"].strip() \
                or not nonnegative_count(room.get("processors")) or room["processors"] == 0 \
                or not nonnegative_count(room.get("maxHeapBytes")) or room["maxHeapBytes"] == 0:
            errors.append("actual JVM process metadata missing or invalid")
        if {key: room.get(key) for key in PROCESS_FIELDS} != process:
            errors.append("room reports disagree about the same JVM process")
        if (room.get("engine"), room.get("seed"), room.get("rooms")) != (engine, seed, room_count):
            errors.append("room metadata mismatch")
        if not room.get("success") or room.get("elapsedTestTicks", 0) < 6000:
            errors.append("room did not finish the full 6000-tick observation")
        if room.get("serverTickMeasurement") != "forge-start-highest-through-end-lowest":
            errors.append("MSPT must include the Forge END scheduler; vanilla tickTimes is incomplete")
        if room.get("schedulerCoverageSamples", 0) < 5900 or room.get("maxUncoveredSchedulerNanos") != 0 \
                or room.get("lastMeasuredCompletedServerTick") != room.get("lastMeasuredSchedulerTick"):
            errors.append("inclusive MSPT did not verify the matching completed scheduler ticks")
        if room.get("spawned") != count or room.get("completed") != count:
            errors.append("not every original entity arrived")
        if room.get("requeued") != 0 or room.get("discarded") != 0 or room.get("recycleEvents"):
            errors.append("original entities were recycled")
        if not room.get("navigationReferencesReleased") or not room.get("realRecyclerInstalled"):
            errors.append("cleanup or actual recycler verification missing")
        entities = room.get("entities", [])
        if len(entities) != count or len({entity.get("uuid") for entity in entities}) != count:
            errors.append("original entity UUID evidence incomplete")
        if any(not entity.get("alive") or not entity.get("sameEntity")
               or not finite(entity.get("arrivalTick")) or entity["arrivalTick"] > 950 for entity in entities):
            errors.append("entity was replaced, removed, or missed its frozen arrival deadline")
        for phase in (room, room.get("coldInitialPursuit"), room.get("retainedCacheSustainedPursuit")):
            if not phase or phase.get("serverTickSamples", 0) <= 0 or any(
                    not finite(phase.get(key)) for key in ("serverTickP95Millis", "serverTickP99Millis")):
                errors.append("MSPT measurement absent")
        if engine == "layered":
            for key, limit in (("maxRoomExpansionsPerTick", 1024), ("maxRoomGeometryPerTick", 256),
                               ("maxGlobalExpansionsPerTick", 4096), ("maxGlobalGeometryPerTick", 1024)):
                if not nonnegative_count(room.get(key)) or room[key] > limit:
                    errors.append(f"missing or exceeded work budget: {key}")
            capacity.append(inspect_capacity(room, count, errors))
    if not launch or not launch.get("jfrEnabled") or not (directory / "navigation.jfr").is_file():
        errors.append("matching JFR collection is required for both engines")
    if not profile or not profile.get("measurementWindowEpochMillis"):
        errors.append("workload-scoped JFR summary missing")
    elif profile.get("serverThreadExecutionSamples", 0) <= 0:
        errors.append("JFR has no server-thread samples inside the workload window")
    manifest = directory / "compiled-addon-classes.sha256"
    digest = hashlib.sha256(manifest.read_bytes()).hexdigest() if manifest.is_file() else None
    if digest is None:
        errors.append("compiled class manifest missing")
    environment = None if not launch else {
        key: launch.get(key) for key in ("forge", "javaExecutable", "os", "arch", "processors")}
    if environment is not None:
        environment["vmFlags"] = sorted(arg for arg in jvm_arguments
                                         if arg.startswith("-X") and not arg.startswith("-XX:StartFlightRecording"))
        environment["navigationDebugEnabled"] = launch.get("navigationDebugEnabled")
        environment["targetMotionPolicy"] = launch.get("targetMotionPolicy")
        environment["actualProcess"] = process
    return dict(directory=str(directory), engine=engine, workload=workload, seed=seed,
                targetMotionPolicy=expected_policy, targetMotionObservations=motion,
                evidencePassed=not errors, errors=sorted(set(errors)), classManifestSha256=digest,
                environment=environment, rooms=rooms, capacityObservations=capacity,
                nativePathStackSamples=None if not profile else profile.get("nativePathStackSamples"))


def compare(old, new):
    reasons, phases = [], {}
    if not old["evidencePassed"] or not new["evidencePassed"]:
        reasons.append("both functional/evidence runs must pass before making a performance comparison")
    if old["classManifestSha256"] != new["classManifestSha256"]:
        reasons.append("the two engines did not run the same compiled classes")
    if old["environment"] != new["environment"]:
        reasons.append("runtime environment or VM options differ")
    if old["targetMotionPolicy"] != new["targetMotionPolicy"]:
        reasons.append("target motion workloads differ; static-pressure and moving-reference evidence cannot be mixed")
    # Multi-room reports observe the same server ticks; do not add/average their percentiles.
    a = next((room for room in old["rooms"] if room.get("roomIndex") == 0), {})
    b = next((room for room in new["rooms"] if room.get("roomIndex") == 0), {})
    for label in ("overall", "coldInitialPursuit", "retainedCacheSustainedPursuit"):
        left, right = (a, b) if label == "overall" else (a.get(label) or {}, b.get(label) or {})
        values = {}
        for percentile in (95, 99):
            key = f"serverTickP{percentile}Millis"
            baseline, current = left.get(key), right.get(key)
            limit = max(baseline * 1.10, baseline + 2.0) if finite(baseline) else None
            values[f"p{percentile}"] = dict(legacyMillis=baseline, layeredMillis=current, limitMillis=limit,
                                             passed=finite(current) and limit is not None and current <= limit)
        values["layeredP95Within50Millis"] = finite(right.get("serverTickP95Millis")) and right["serverTickP95Millis"] <= 50
        phases[label] = values
    # The frozen publication threshold applies to the full workload. Phase comparisons remain visible diagnostics.
    overall = phases["overall"]
    if not finite(a.get("serverTickP95Millis")) or a["serverTickP95Millis"] > 50:
        reasons.append("legacy P95 does not establish a comparable reference below 50 ms")
    if not all(overall[f"p{p}"]["passed"] for p in (95, 99)) or not overall["layeredP95Within50Millis"]:
        reasons.append("overall frozen performance threshold not met or unmeasured")
    return dict(workload=new["workload"], seed=new["seed"], targetMotionPolicy=new["targetMotionPolicy"],
                passed=not reasons, reasons=reasons, phases=phases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target-motion", choices=TARGET_MOTION_POLICIES, default="after-deadline",
                        help="Require this exact policy in launch arguments and every room report.")
    args = parser.parse_args()
    runs, pairs = [], []
    for workload in ("8", "32", "64", "multi"):
        for seed in (1701, 1702, 1703):
            pair = [inspect(args.results_root / f"{engine}-{workload}-{seed}", engine, workload, seed, args.target_motion)
                    for engine in ("legacy", "layered")]
            runs.extend(pair)
            pairs.append(compare(*pair))
    same_binary = len({run["classManifestSha256"] for run in runs}) == 1 and all(
        run["classManifestSha256"] for run in runs)
    result = dict(expectedRuns=24, targetMotionPolicy=args.target_motion,
                  evidencePassedRuns=sum(run["evidencePassed"] for run in runs),
                  sameCompiledBinaryForWholeMatrix=same_binary, comparisons=pairs,
                  frozenCapacityLimits=dict(roomTransient=ROOM_TRANSIENT_LIMIT,
                                            serverTransient=SERVER_TRANSIENT_LIMIT,
                                            cache=CACHE_LIMITS, searchRecords=SEARCH_RECORD_LIMIT),
                  passed=bool(same_binary and all(pair["passed"] for pair in pairs)),
                  notes=["Cold and retained-cache phases are different pursuit workloads, not a causal cache-speed comparison.",
                         "The fixed phase windows remain ticks 21..950 and 951..6000; after-first-arrival includes moving pursuit in the first window.",
                         "After-first-arrival compares the same event-relative policy, not identical absolute-tick player inputs; each room's actual start is reported.",
                         "A new moving-reference result does not resolve or replace failures from the original after-deadline static-pressure workload.",
                         "Transient usage has a recorded high-water mark; resident cache/search checks cover snapshots every 100 ticks and phase boundaries, not every tick.",
                         "JFR stack samples are not exact native path-search times or counts.",
                         "This report cannot substitute for deferred actual-map acceptance."],
                  runs=[{key: value for key, value in run.items() if key != "rooms"} for run in runs])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("expectedRuns", "evidencePassedRuns", "sameCompiledBinaryForWholeMatrix", "passed")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
