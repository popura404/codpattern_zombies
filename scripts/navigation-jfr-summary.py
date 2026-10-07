#!/usr/bin/env python3
"""Summarize sampled server-thread stacks and GC from an opt-in navigation JFR.

Usage: python3 scripts/navigation-jfr-summary.py RESULTS_DIRECTORY
Execution samples estimate stack occupancy; they are not exact path-search counts or timers.
"""
import collections
import datetime
import json
from pathlib import Path
import subprocess
import sys


directory = Path(sys.argv[1]).resolve()
recording = directory / "navigation.jfr"
if not recording.is_file():
    raise SystemExit(f"No JFR recording: {recording}")
result = subprocess.run(
    ["jfr", "print", "--json", "--events",
     "jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GarbageCollection", str(recording)],
    check=True, capture_output=True, text=True)
events = json.loads(result.stdout)["recording"]["events"]
reports = [json.loads(path.read_text()) for path in directory.glob("load-*-room-*.json")]
starts = [report["measurementStartedEpochMillis"] for report in reports if report.get("measurementStartedEpochMillis")]
ends = [report["measurementFinishedEpochMillis"] for report in reports if report.get("measurementFinishedEpochMillis")]
window = (min(starts), max(ends)) if starts and ends else None
stacks = collections.Counter()
leaf_methods = collections.Counter()
total = server = native_path = addon_navigation = 0
gc = []
for event in events:
    values = event["values"]
    instant = datetime.datetime.fromisoformat(values["startTime"].replace("Z", "+00:00")).timestamp() * 1000
    in_window = window is None or window[0] <= instant <= window[1]
    if event["type"] == "jdk.GarbageCollection":
        gc.append(dict({key: values.get(key) for key in
                        ("startTime", "duration", "name", "cause", "sumOfPauses", "longestPause")},
                       inMeasurementWindow=in_window))
        continue
    if not in_window:
        continue
    total += 1
    thread = values.get("sampledThread") or {}
    if thread.get("javaName") != "Server thread" and thread.get("javaThreadName") != "Server thread":
        continue
    server += 1
    frames = (values.get("stackTrace") or {}).get("frames") or []
    methods = [frame["method"]["type"]["name"].replace("/", ".") + "." + frame["method"]["name"]
               for frame in frames if frame.get("method")]
    if not methods:
        continue
    leaf_methods[methods[0]] += 1
    stacks[" <- ".join(methods[:8])] += 1
    native_path += any("net.minecraft.world.level.pathfinder." in method
                       or "net.minecraft.world.entity.ai.navigation." in method for method in methods)
    addon_navigation += any("com.cdp.codpattern.app.zombies.service.navigation." in method
                            or "ZombiesGroundNavigationService" in method for method in methods)
summary = {
    "recording": str(recording), "profilingEnabled": True,
    "sampleScope": "load observation after warmup" if window else "full JVM lifetime; workload window unavailable",
    "measurementWindowEpochMillis": window,
    "method": "JDK profile-settings execution/native samples filtered to Server thread; stack occupancy estimates, not exact search counts or elapsed timers. GC events span the full JVM lifetime including startup. Both engines must use the same profiling option.",
    "allThreadExecutionSamples": total, "serverThreadExecutionSamples": server,
    "nativePathStackSamples": native_path,
    "nativePathFractionOfServerSamples": native_path / server if server else None,
    "addonNavigationStackSamples": addon_navigation,
    "addonNavigationFractionOfServerSamples": addon_navigation / server if server else None,
    "topServerLeafMethods": leaf_methods.most_common(20),
    "topServerStacks": stacks.most_common(20), "garbageCollections": gc,
}
(directory / "jfr-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps({key: summary[key] for key in
                  ("serverThreadExecutionSamples", "nativePathStackSamples", "addonNavigationStackSamples")}))
