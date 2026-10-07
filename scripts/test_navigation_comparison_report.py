#!/usr/bin/env python3
"""Synthetic report contract tests; these fixtures are not navigation evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("navigation-comparison-report.py")
SPEC = importlib.util.spec_from_file_location("navigation_comparison_report", SCRIPT)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def cache():
    return {**{key: 0 for key in REPORT.CACHE_LIMITS}, "pinnedTiles": 0,
            "transientEntries": 12, "peakTransientEntries": 32, "transientLimit": 65536}


def scheduler():
    return dict(transientEntries=24, peakTransientEntries=64, transientLimit=262144)


def room(engine, workload, seed, index, target_motion="after-deadline"):
    count, rooms = (32, 4) if workload == "multi" else (int(workload), 1)
    phase = dict(serverTickSamples=100, serverTickP95Millis=5.0, serverTickP99Millis=7.0,
                 cacheAtStart=cache(), cacheAtEnd=cache())
    planning = dict(searchRecords=12, activeRequests=count)
    reference = target_motion == "after-first-arrival"
    motion_start = 300 if reference else 951
    return dict(engine=engine, seed=seed, rooms=rooms, roomIndex=index,
                java="17.0.17", os="Linux amd64", processors=8, maxHeapBytes=4 * 1024 ** 3,
                success=True, elapsedTestTicks=6000,
                firstArrivalTick=300, targetMotionPolicy=target_motion, motionStartedTick=motion_start,
                motionPhaseOriginTick=motion_start if reference else 950,
                maxPlayerStep=0.055 if reference else 0.725, motionFirstStep=0.0 if reference else 0.725,
                playerMotionSamples=6000-motion_start, playerMotionSamplesBeforeDeadline=max(0, 951-motion_start),
                playerMotionWithinPlatform=True if reference else None,
                targetMotionInputComparison="same-event-relative-policy-not-identical-absolute-tick-input"
                if reference else "same-fixed-tick-input",
                serverTickMeasurement="forge-start-highest-through-end-lowest",
                schedulerCoverageSamples=6000, maxUncoveredSchedulerNanos=0,
                lastMeasuredCompletedServerTick=6001, lastMeasuredSchedulerTick=6001,
                spawned=count, completed=count, requeued=0, discarded=0, recycleEvents={},
                navigationReferencesReleased=True, realRecyclerInstalled=True,
                entities=[dict(uuid=f"synthetic-{index}-{i}", alive=True, sameEntity=True, arrivalTick=300)
                          for i in range(count)],
                serverTickSamples=5980, serverTickP95Millis=5.0, serverTickP99Millis=7.0,
                coldInitialPursuit=copy.deepcopy(phase), retainedCacheSustainedPursuit=copy.deepcopy(phase),
                maxRoomExpansionsPerTick=1024, maxRoomGeometryPerTick=256,
                maxGlobalExpansionsPerTick=4096, maxGlobalGeometryPerTick=1024,
                graphCache=cache(), planning=copy.deepcopy(planning), globalSchedulerTiming=scheduler(),
                runtimeSamplesEvery100Ticks=[dict(tick=tick, cache=cache(), planning=copy.deepcopy(planning),
                                                  globalSchedulerTiming=scheduler())
                                            for tick in range(100, 6001, 100)])


def write_json(path, data):
    path.write_text(json.dumps(data))


def write_run(directory, engine="layered", workload="8", seed=1701, target_motion="after-deadline"):
    directory.mkdir(parents=True, exist_ok=True)
    write_json(directory / "run-summary.json",
               dict(success=True, executedTests=1, engine=engine, scenario=workload, seed=seed))
    write_json(directory / "world-environment.json", dict(
        verified=True, actualWorldSeed=seed, actualOverworldGenerator="net.minecraft.world.level.levelgen.FlatLevelSource",
        generateStructures=False, compiledClassesVerified=True, actualWorldDifficulty="easy"))
    write_json(directory / "launch-environment.json", dict(
        forge="47.4.0", javaExecutable="/synthetic/java", os="Linux", arch="amd64", processors=8,
        jvmArgs=["-Xmx4G", "-XX:StartFlightRecording=filename=synthetic.jfr",
                 REPORT.TARGET_MOTION_PROPERTY + "=" + target_motion], jfrEnabled=True,
        navigationDebugEnabled=False, targetMotionPolicy=target_motion))
    write_json(directory / "jfr-summary.json", dict(measurementWindowEpochMillis=[1, 1000], serverThreadExecutionSamples=5))
    (directory / "navigation.jfr").write_text("SYNTHETIC CONTRACT FIXTURE, NOT A RECORDING")
    (directory / "compiled-addon-classes.sha256").write_text("synthetic-identical-class-manifest\n")
    count, rooms = (32, 4) if workload == "multi" else (int(workload), 1)
    for index in range(rooms):
        write_json(directory / f"load-{count}-room-{index}.json", room(engine, workload, seed, index, target_motion))


class ReportContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="navigation-report-synthetic-")
        self.root = Path(self.temporary.name)
        self.directory = self.root / "layered-8-1701"
        write_run(self.directory)

    def tearDown(self):
        self.temporary.cleanup()

    def inspect(self, workload="8", engine="layered", directory=None, target_motion="after-deadline"):
        return REPORT.inspect(directory or self.directory, engine, workload, 1701, target_motion)

    def modify_room(self, mutate, index=0, workload="8", directory=None):
        path = (directory or self.directory) / f"load-{32 if workload == 'multi' else workload}-room-{index}.json"
        data = json.loads(path.read_text())
        mutate(data)
        write_json(path, data)

    def modify_launch(self, mutate):
        path = self.directory / "launch-environment.json"
        data = json.loads(path.read_text())
        mutate(data)
        write_json(path, data)

    def test_debug_disabled_requires_explicit_boolean_metadata(self):
        for value in (None, True, 0, "false"):
            with self.subTest(value=value):
                write_run(self.directory)
                self.modify_launch(lambda data: data.update(navigationDebugEnabled=value))
                result = self.inspect()
                self.assertFalse(result["evidencePassed"])
                self.assertIn("final load evidence requires explicit navigationDebugEnabled=false", result["errors"])
        write_run(self.directory)
        self.modify_launch(lambda data: data.pop("navigationDebugEnabled"))
        self.assertFalse(self.inspect()["evidencePassed"], "older evidence without the explicit field is unverified")

    def test_actual_debug_true_is_rejected_in_every_case_and_duplicate_order(self):
        property_name = REPORT.DEBUG_PROPERTY
        for arguments in ([property_name + "=true"], [property_name + "=TRUE"], [property_name + "=TrUe"],
                          [property_name + "=true", property_name + "=false"],
                          [property_name + "=false", property_name + "=true"]):
            with self.subTest(arguments=arguments):
                write_run(self.directory)
                self.modify_launch(lambda data: data["jvmArgs"].extend(arguments))
                result = self.inspect()
                self.assertFalse(result["evidencePassed"], "metadata=false cannot hide debug JVM arguments")
                self.assertIn("navigation debug JVM property must be explicitly false when present", result["errors"])

    def test_only_false_or_absent_debug_property_is_final_evidence(self):
        for value in ("false", "FALSE", "FaLsE"):
            with self.subTest(value=value):
                write_run(self.directory)
                self.modify_launch(lambda data: data["jvmArgs"].append(REPORT.DEBUG_PROPERTY + "=" + value))
                self.assertTrue(self.inspect()["evidencePassed"])
        for argument in (REPORT.DEBUG_PROPERTY, REPORT.DEBUG_PROPERTY + "=", REPORT.DEBUG_PROPERTY + "=0",
                         REPORT.DEBUG_PROPERTY + "=true "):
            with self.subTest(argument=argument):
                write_run(self.directory)
                self.modify_launch(lambda data: data["jvmArgs"].append(argument))
                self.assertFalse(self.inspect()["evidencePassed"])

    def test_missing_or_malformed_actual_arguments_do_not_claim_debug_disabled(self):
        for value in (None, "-Xmx4G", [None]):
            with self.subTest(value=value):
                write_run(self.directory)
                self.modify_launch(lambda data: data.update(jvmArgs=value))
                result = self.inspect()
                self.assertFalse(result["evidencePassed"])
                self.assertTrue(any("debug mode cannot be verified" in error for error in result["errors"]))
        write_run(self.directory)
        self.modify_launch(lambda data: data.pop("jvmArgs"))
        self.assertFalse(self.inspect()["evidencePassed"])

    def test_valid_fixture_is_accepted_without_claiming_unsampled_peaks(self):
        result = self.inspect()
        self.assertTrue(result["evidencePassed"], result["errors"])
        observed = result["capacityObservations"][0]
        self.assertEqual(observed["scheduledSamples"], 60)
        self.assertEqual(observed["transientHighWaterMarks"], {"room": 32, "server": 64})
        self.assertIn("not all-tick", observed["residentMeasurementScope"])

    def test_raising_reported_transient_limits_cannot_hide_excess(self):
        for location, original_limit in (("graphCache", 65536), ("globalSchedulerTiming", 262144)):
            with self.subTest(location=location):
                write_run(self.directory)
                self.modify_room(lambda data: data[location].update(
                    transientLimit=original_limit * 2, peakTransientEntries=original_limit + 1))
                result = self.inspect()
                self.assertFalse(result["evidencePassed"])
                self.assertTrue(any("frozen" in error for error in result["errors"]))

    def test_even_low_usage_does_not_authorize_a_changed_limit(self):
        self.modify_room(lambda data: data["runtimeSamplesEvery100Ticks"][2]["cache"].update(transientLimit=131072))
        self.assertFalse(self.inspect()["evidencePassed"])

    def test_every_resident_limit_checks_intermediate_samples(self):
        for field, limit in REPORT.CACHE_LIMITS.items():
            with self.subTest(field=field):
                write_run(self.directory)
                self.modify_room(lambda data: data["runtimeSamplesEvery100Ticks"][7]["cache"].update({field: limit + 1}))
                result = self.inspect()
                self.assertFalse(result["evidencePassed"])
                self.assertTrue(any(f"tick 800.cache.{field}" in error for error in result["errors"]))

    def test_exact_frozen_boundaries_are_valid(self):
        def change(data):
            data["runtimeSamplesEvery100Ticks"][0]["cache"].update(REPORT.CACHE_LIMITS)
            data["runtimeSamplesEvery100Ticks"][0]["planning"]["searchRecords"] = 65536
            data["graphCache"].update(transientEntries=65536, peakTransientEntries=65536)
            data["globalSchedulerTiming"].update(transientEntries=262144, peakTransientEntries=262144)
        self.modify_room(change)
        self.assertTrue(self.inspect()["evidencePassed"])

    def test_search_capacity_and_missing_samples_fail(self):
        self.modify_room(lambda data: data["runtimeSamplesEvery100Ticks"][10]["planning"].update(searchRecords=65537))
        self.assertFalse(self.inspect()["evidencePassed"])
        write_run(self.directory)
        self.modify_room(lambda data: data["runtimeSamplesEvery100Ticks"].pop(10))
        self.assertFalse(self.inspect()["evidencePassed"])

    def test_missing_or_negative_counters_cannot_become_zero(self):
        for value in (None, -1, True, float("nan")):
            with self.subTest(value=value):
                write_run(self.directory)
                self.modify_room(lambda data: data["graphCache"].update(nodes=value))
                self.assertFalse(self.inspect()["evidencePassed"])

    def test_room_process_metadata_must_match_and_include_java(self):
        multi = self.root / "layered-multi-1701"
        write_run(multi, workload="multi")
        self.assertTrue(self.inspect(workload="multi", directory=multi)["evidencePassed"])
        for field, value in (("java", "17.0.18"), ("maxHeapBytes", 1024 ** 3), ("processors", 2), ("os", "Other")):
            with self.subTest(field=field):
                write_run(multi, workload="multi")
                self.modify_room(lambda data: data.update({field: value}), 3, "multi", multi)
                self.assertFalse(self.inspect(workload="multi", directory=multi)["evidencePassed"])
        self.modify_room(lambda data: data.pop("java"))
        self.assertFalse(self.inspect()["evidencePassed"])

    def test_changed_java_at_same_executable_rejects_engine_comparison(self):
        legacy = self.root / "legacy-8-1701"
        write_run(legacy, engine="legacy")
        old, new = self.inspect(engine="legacy", directory=legacy), self.inspect()
        self.assertTrue(REPORT.compare(old, new)["passed"])
        self.modify_room(lambda data: data.update(java="17.0.18"))
        comparison = REPORT.compare(old, self.inspect())
        self.assertFalse(comparison["passed"])
        self.assertIn("runtime environment or VM options differ", comparison["reasons"])

    def test_motion_requires_launch_jvm_and_every_room_to_match_expected_policy(self):
        reference = "after-first-arrival"
        write_run(self.directory, target_motion=reference)
        self.assertTrue(self.inspect(target_motion=reference)["evidencePassed"])
        self.assertFalse(self.inspect()["evidencePassed"], "a reference cannot silently replace pressure evidence")
        self.modify_launch(lambda data: data.pop("targetMotionPolicy"))
        self.assertFalse(self.inspect(target_motion=reference)["evidencePassed"])
        for arguments in ([], [REPORT.TARGET_MOTION_PROPERTY + "=after-deadline"],
                          [REPORT.TARGET_MOTION_PROPERTY + "=after-deadline",
                           REPORT.TARGET_MOTION_PROPERTY + "=" + reference]):
            write_run(self.directory, target_motion=reference)
            self.modify_launch(lambda data: data.update(jvmArgs=["-Xmx4G"] + arguments))
            self.assertFalse(self.inspect(target_motion=reference)["evidencePassed"])
        multi = self.root / "layered-multi-1701"
        write_run(multi, workload="multi", target_motion=reference)
        self.assertTrue(self.inspect(workload="multi", directory=multi, target_motion=reference)["evidencePassed"])
        self.modify_room(lambda data: data.update(targetMotionPolicy="after-deadline"), 3, "multi", multi)
        self.assertFalse(self.inspect(workload="multi", directory=multi, target_motion=reference)["evidencePassed"])

    def test_reference_requires_bounded_continuous_motion_and_actual_start(self):
        for change in (dict(maxPlayerStep=0.060001), dict(maxPlayerStep=0), dict(maxPlayerStep=None), dict(motionFirstStep=0.0001),
                       dict(motionFirstStep=False), dict(playerMotionWithinPlatform=False),
                       dict(motionStartedTick=301), dict(motionPhaseOriginTick=299),
                       dict(playerMotionSamples=5699), dict(playerMotionSamplesBeforeDeadline=650),
                       dict(firstArrivalTick=299, motionStartedTick=299, motionPhaseOriginTick=299,
                            playerMotionSamples=5701, playerMotionSamplesBeforeDeadline=652),
                       dict(targetMotionInputComparison="same-fixed-tick-input")):
            with self.subTest(change=change):
                write_run(self.directory, target_motion="after-first-arrival")
                self.modify_room(lambda data: data.update(change))
                self.assertFalse(self.inspect(target_motion="after-first-arrival")["evidencePassed"])
        write_run(self.directory, target_motion="after-first-arrival")
        self.modify_room(lambda data: data.update(maxPlayerStep=0.06))
        self.assertTrue(self.inspect(target_motion="after-first-arrival")["evidencePassed"])

    def test_same_event_policy_can_have_different_observed_start_ticks(self):
        legacy = self.root / "legacy-8-1701"
        write_run(legacy, engine="legacy", target_motion="after-first-arrival")
        write_run(self.directory, target_motion="after-first-arrival")
        self.modify_room(lambda data: data.update(firstArrivalTick=305, motionStartedTick=305,
                         motionPhaseOriginTick=305, playerMotionSamples=5695, playerMotionSamplesBeforeDeadline=646))
        self.modify_room(lambda data: [entity.update(arrivalTick=305) for entity in data["entities"]])
        old = self.inspect(engine="legacy", directory=legacy, target_motion="after-first-arrival")
        new = self.inspect(target_motion="after-first-arrival")
        self.assertTrue(REPORT.compare(old, new)["passed"])
        self.assertNotEqual(old["targetMotionObservations"][0]["motionStartedTick"],
                            new["targetMotionObservations"][0]["motionStartedTick"])
        write_run(legacy, engine="legacy")
        mixed = REPORT.compare(self.inspect(engine="legacy", directory=legacy), new)
        self.assertFalse(mixed["passed"])
        self.assertTrue(any("workloads differ" in reason for reason in mixed["reasons"]))

    def test_original_pressure_default_and_initial_jump_are_not_rewritten(self):
        self.modify_launch(lambda data: data.update(jvmArgs=["-Xmx4G"]))
        self.assertTrue(self.inspect()["evidencePassed"], "the original default does not need a JVM override")
        self.modify_room(lambda data: data.update(motionStartedTick=950))
        self.assertFalse(self.inspect()["evidencePassed"])

    def test_reference_cli_is_explicit_and_rejects_one_mixed_cell(self):
        for workload in ("8", "32", "64", "multi"):
            for seed in (1701, 1702, 1703):
                for engine in ("legacy", "layered"):
                    write_run(self.root / f"{engine}-{workload}-{seed}", engine, workload, seed, "after-first-arrival")
        output = self.root / "synthetic-reference-comparison.json"
        command = [sys.executable, str(SCRIPT), "--results-root", str(self.root), "--output", str(output)]
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 1)
        command += ["--target-motion", "after-first-arrival"]
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
        self.assertEqual(json.loads(output.read_text())["targetMotionPolicy"], "after-first-arrival")
        write_run(self.directory)
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 1)
        self.assertEqual(json.loads(output.read_text())["evidencePassedRuns"], 23)

    def test_full_cli_requires_all_24_cells(self):
        for workload in ("8", "32", "64", "multi"):
            for seed in (1701, 1702, 1703):
                for engine in ("legacy", "layered"):
                    write_run(self.root / f"{engine}-{workload}-{seed}", engine, workload, seed)
        output = self.root / "synthetic-comparison.json"
        command = [sys.executable, str(SCRIPT), "--results-root", str(self.root), "--output", str(output)]
        accepted = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(accepted.returncode, 0, accepted.stderr + accepted.stdout)
        self.assertEqual(json.loads(output.read_text())["evidencePassedRuns"], 24)
        self.modify_launch(lambda data: data["jvmArgs"].append(REPORT.DEBUG_PROPERTY + "=TRUE"))
        diagnostic = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(diagnostic.returncode, 1, diagnostic.stderr + diagnostic.stdout)
        self.assertFalse(json.loads(output.read_text())["passed"])
        self.assertEqual(json.loads(output.read_text())["evidencePassedRuns"], 23)
        write_run(self.directory)
        self.modify_room(lambda data: data["runtimeSamplesEvery100Ticks"][1]["cache"].update(nodes=65537))
        excessive = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(excessive.returncode, 1, excessive.stderr + excessive.stdout)
        self.assertFalse(json.loads(output.read_text())["passed"])
        write_run(self.directory)
        shutil.rmtree(self.root / "legacy-multi-1703")
        incomplete = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(incomplete.returncode, 1, incomplete.stderr + incomplete.stdout)
        result = json.loads(output.read_text())
        self.assertFalse(result["passed"])
        self.assertEqual(result["evidencePassedRuns"], 23)


if __name__ == "__main__":
    unittest.main(verbosity=2)
