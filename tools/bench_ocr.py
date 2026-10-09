#!/usr/bin/env python3
"""Generate explicit synthetic workloads or collect real adb process evidence.

This tool never supplies OCR answers. Timing and memory collection are separate
from recognition/replay, and physical acceptance is never inferred from emulator.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import re
import statistics
import subprocess
import time
import uuid
from datetime import datetime, timezone

from run_native_validation import transfer_private_fixture

WORKLOADS = {
    "W0": (1, 100, 2000, 1),
    "W1": (20, 2000, 40000, 1),
    "W2": (50, 10000, 200000, 1),
    "W3": (20, 2000, 40000, 300),
}
PATHS = ("B0-manual", "B0-auto", "B1", "B2", "B3")
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")


def write_json(path: pathlib.Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def generate(args: argparse.Namespace) -> None:
    lines, count, points, pages = WORKLOADS[args.workload]
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schemaVersion": 1, "workload": args.workload,
        "provenance": {"kind": "synthetic", "purpose": "load-only", "seed": args.seed},
        "qualityAcceptance": "not applicable: geometric traces are not handwritten text",
        "pages": [],
    }
    per_line = count // lines
    for page_number in range(pages):
        objects = []
        for index in range(count):
            line, column = divmod(index, per_line)
            # Connected loops exercise temporal order, dense layout and late dots.
            # They carry no language ground truth and are not claimed to be letters.
            x = 40 + column * 900 / per_line
            y = 50 + line * 1300 / lines
            phase = (args.seed + index) % 17
            trace = [
                {"x": x + i * 3 / 19, "y": y + math.sin((i + phase) * math.pi / 9) * 5,
                 "p": .5, "tiltX": 0, "tiltY": 0, "t": index * 200 + i * 8}
                for i in range(points // count)
            ]
            objects.append({
                "id": f"p{page_number}-s{index}", "type": "stroke", "brush": "fountain",
                "width": 2, "color": "#172033", "points": trace,
                "captureSeq": index, "captureSessionId": f"synthetic-{args.seed}-p{page_number}",
                "timeBasis": "synthetic-monotonic", "strokeRevision": 1,
            })
        page_id = f"synthetic-{args.workload}-{page_number}"
        page = {"schemaVersion": 1, "sampleId": page_id, "provenance": manifest["provenance"],
                "id": page_id, "width": 1000, "height": 1414, "objects": objects}
        output = args.output / f"{page_id}.ink.json"
        # Stream each page to disk; W3 never materializes the whole 300-page note.
        with output.open("w", encoding="utf-8") as stream:
            json.dump(page, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n")
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        manifest["pages"].append({"id": page_id, "file": output.name, "sha256": digest,
                                  "strokes": count, "points": points})
    write_json(args.output / "manifest.json", manifest)
    print(json.dumps({"workload": args.workload, "pages": pages, "kind": "synthetic", "output": str(args.output)}))


def adb(args: argparse.Namespace, *command: str, timeout: float = 30) -> str:
    result = subprocess.run(
        [args.adb, "-s", args.device, *command],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
    )
    return result.stdout.strip()


def replay_output_path(output: str) -> str:
    lines = [line.strip() for line in output.splitlines()]
    if ("INSTRUMENTATION_FAILED" in output or "INSTRUMENTATION_RESULT: error=" in output
            or "shortMsg=Process crashed" in output or "INSTRUMENTATION_RESULT: passed=false" in lines
            or lines.count("INSTRUMENTATION_RESULT: ocrReplay=actual_debug_replay_saved") != 1
            or lines.count("INSTRUMENTATION_RESULT: passed=true") != 1
            or [line for line in lines if line.startswith("INSTRUMENTATION_CODE:")] != ["INSTRUMENTATION_CODE: 0"]):
        raise ValueError("Instrumentation failed; no safe successful replay output")
    matches = re.findall(r"^INSTRUMENTATION_RESULT: ocrReplayOutput=(.+)$", output, re.MULTILINE)
    if len(matches) != 1 or not re.fullmatch(r"ocr-replay-results/[A-Za-z0-9_-]+\.json", matches[0].strip()):
        raise ValueError("Instrumentation did not return one safe private replay output path")
    return matches[0].strip()


def instrumentation_metadata(output: str) -> dict:
    """Keep error type and stack frames without arbitrary exception messages."""
    metadata = {"codes": re.findall(r"^INSTRUMENTATION_CODE: (.+)$", output, re.MULTILINE)}
    for name in ("ocrReplay", "ocrReplayOutput", "passed"):
        values = re.findall(r"^INSTRUMENTATION_RESULT: " + name + r"=(.+)$", output, re.MULTILINE)
        if values:
            metadata[name] = values
    errors = re.findall(r"^INSTRUMENTATION_RESULT: error=([A-Za-z0-9_.$]+)", output, re.MULTILINE)
    if errors:
        metadata["errorTypes"] = errors
        metadata["stackFrames"] = re.findall(r"^\s+at ([A-Za-z0-9_.$<>]+\([^\r\n]*\))", output, re.MULTILINE)
    metadata["failed"] = bool(errors) or "INSTRUMENTATION_FAILED" in output
    return metadata


def runtime_timing(diagnostic: dict, path: str, feature: str, state: str) -> dict:
    execution = diagnostic.get("execution", {})
    measurements = diagnostic.get("measurements", {})
    result = diagnostic.get("result", {})
    if state == "warm" or path == "B3" and feature == "cache":
        process = execution.get("processId")
        warmup = execution.get("warmupMs")
        if (not isinstance(process, int) or isinstance(process, bool) or process <= 0 or
                execution.get("warmupPerformed") is not True or execution.get("warmupStatus") != "complete" or
                not isinstance(warmup, (int, float)) or isinstance(warmup, bool) or
                not math.isfinite(warmup) or warmup < 0):
            raise ValueError("Warm timing requires completed same-process runtime warmup evidence")
    elapsed = measurements.get("totalMs", result.get("measurements", {}).get("totalMs"))
    scope = execution.get("measurementScope", "runtime replay including preparation and persistence; host time recorded separately")
    if path == "B3" and feature == "cache":
        elapsed = result.get("measurements", {}).get("totalMs")
        scope = "warm cache coordinator request; cold warmup and replay setup excluded"
    if not isinstance(elapsed, (int, float)) or isinstance(elapsed, bool) or not math.isfinite(elapsed) or elapsed < 0:
        raise ValueError("Replay did not provide a measured nonnegative runtime duration")
    return {"totalMs": elapsed, "measurementScope": scope,
            "replayTotalMs": measurements.get("replayTotalMs", measurements.get("totalMs")),
            "processId": execution.get("processId"), "warmupMs": execution.get("warmupMs"),
            "warmupStatus": execution.get("warmupStatus", "notRequested")}


def replay(args: argparse.Namespace) -> None:
    """Explicit debug-only input replay; source text never appears in console logs."""
    if not PACKAGE.fullmatch(args.package) or not args.package.endswith(".debug"):
        raise ValueError("Replay requires an explicit debug package")
    if args.input.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("Replay one page at a time; input exceeds 32 MiB")
    raw = args.input.read_bytes()
    source = json.loads(raw.decode("utf-8-sig"))
    if not isinstance(source, dict) or source.get("provenance", {}).get("kind") not in ("real", "synthetic"):
        raise ValueError("Replay requires explicit real or synthetic input provenance")
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb, "-s", args.device]
    filename = "replay-" + uuid.uuid4().hex + ".json"
    private_input = "files/ocr-replay/" + filename
    receipt = {"schemaVersion": 1, "inputSha256": hashlib.sha256(raw).hexdigest(),
               "deviceId": args.device, "package": args.package, "path": args.path,
               "state": args.state, "stateSource": "operator-declared; compare with runtime model states",
               "physicalAcceptance": "unmeasured", "status": "running"}
    try:
        qemu = adb(args, "shell", "getprop", "ro.kernel.qemu")
        hardware = adb(args, "shell", "getprop", "ro.hardware")
        fingerprint = adb(args, "shell", "getprop", "ro.build.fingerprint")
        receipt.update(deviceKind="emulator" if qemu == "1" or hardware in ("ranchu", "goldfish") else "physical",
                       deviceProperties={"qemu": qemu, "hardware": hardware, "fingerprint": fingerprint,
                                         "api": adb(args, "shell", "getprop", "ro.build.version.sdk")})
        paths = [line.removeprefix("package:") for line in adb(args, "shell", "pm", "path", args.package).splitlines()]
        if len(paths) != 1 or not paths[0].startswith("/data/app/") or not paths[0].endswith(".apk"):
            raise ValueError("Replay expects one installed debug APK; split/unknown packages are unsupported")
        installed = args.output / ("installed-debug-" + uuid.uuid4().hex + ".apk")
        adb(args, "pull", paths[0], str(installed), timeout=120)
        digest = hashlib.sha256()
        with installed.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        receipt["buildSha"] = digest.hexdigest()
        installed.unlink()
        adb(args, "shell", "run-as", args.package, "mkdir", "-p", "files/ocr-replay")
        receipt["inputTransfer"] = transfer_private_fixture(prefix, args.input, private_input, args.package)
        if receipt["inputTransfer"]["sha256"] != receipt["inputSha256"]:
            raise ValueError("Replay input changed before its verified transfer")
        extras = ["-e", "ocrInput", filename, "-e", "ocrPath", args.path,
                  "-e", "ocrPolicy", args.policy, "-e", "ocrFeature", args.feature,
                  "-e", "ocrWarmup", str(args.state == "warm").lower()]
        started = time.monotonic()
        output = adb(args, "shell", "am", "instrument", "-w", *extras,
                     args.package + ".test/com.inkforge.notesstudio.NativeSmokeInstrumentation", timeout=args.timeout)
        receipt["hostInstrumentationElapsedMs"] = round((time.monotonic() - started) * 1000, 2)
        receipt["instrumentation"] = instrumentation_metadata(output)
        write_json(args.output / "instrumentation-metadata.json", receipt["instrumentation"])
        # Record instrumentation metadata only. Results/ink remain in the
        # explicitly requested diagnostic JSON, rather than ordinary logs.
        path = replay_output_path(output)
        observed = subprocess.run([*prefix, "exec-out", "run-as", args.package, "cat", "files/" + path],
                                  capture_output=True, check=True, timeout=120).stdout
        diagnostic = json.loads(observed.decode("utf-8-sig"))
        page = source.get("sourcePage", source)
        if (diagnostic.get("pageId") != page.get("id") or
                diagnostic.get("sampleId") != source.get("sampleId", page.get("id")) or
                diagnostic.get("provenance", {}).get("kind") != source["provenance"]["kind"] or
                diagnostic.get("execution", {}).get("path") != args.path or
                diagnostic.get("execution", {}).get("policy") != args.policy):
            raise ValueError("Replay output does not match the requested input and execution")
        (args.output / "diagnostic.json").write_bytes(observed)
        result = diagnostic.get("result", {})
        status = result.get("status", "missingResult")
        unavailable = result.get("errorCode") in ("baselineUnavailable", "truthRegionsRequired")
        receipt.update(status="recorded", recognitionStatus=status, errorCode=result.get("errorCode"),
                       diagnosticSha256=hashlib.sha256(observed).hexdigest(),
                       pageDigest=diagnostic.get("pageDigest"), execution=diagnostic.get("execution"))
        if not unavailable and status in ("complete", "partial", "failed", "cancelled"):
            timing = runtime_timing(diagnostic, args.path, args.feature, args.state)
            receipt["stateSource"] = ("runtime-verified same-process warmup" if args.state == "warm" else
                "fresh process with explicit cache preparation" if args.path == "B3" and args.feature == "cache" else
                "fresh instrumentation process; no requested warmup")
            run = {key: receipt[key] for key in ("deviceId", "deviceKind", "buildSha", "path", "state")}
            run.update(inputDigest=receipt["inputSha256"], workload=args.workload, status=status,
                       **timing,
                       inputKind=source["provenance"]["kind"], execution=diagnostic.get("execution"))
            write_json(args.output / "timing-run.json", {"runs": [run]})
        else:
            receipt["performanceComparison"] = "unavailable: no measured recognition run for this path"
        print(json.dumps({"status": receipt["status"], "recognitionStatus": status,
                          "errorCode": result.get("errorCode"), "deviceKind": receipt["deviceKind"], "output": str(args.output)}))
        if args.require_complete and status != "complete":
            raise ValueError("Replay recorded an incomplete recognition result")
    except Exception as error:
        receipt.update(status="failed", error=str(error))
        raise
    finally:
        try:
            adb(args, "shell", "run-as", args.package, "rm", "-f", private_input)
        except Exception:
            receipt["privateInputCleanup"] = "not confirmed"
        write_json(args.output / "replay-receipt.json", receipt)


def parse_memory(output: str) -> dict:
    def number(pattern: str) -> int | None:
        match = re.search(pattern, output, re.MULTILINE)
        return int(match.group(1)) if match else None
    return {
        "pssKiB": number(r"TOTAL PSS:\s*(\d+)") or number(r"^\s*TOTAL\s+(\d+)\s+"),
        "rssKiB": number(r"TOTAL RSS:\s*(\d+)"),
        "nativeHeapPssKiB": number(r"^\s*Native Heap\s+(\d+)\s+"),
        "dalvikHeapPssKiB": number(r"^\s*Dalvik Heap\s+(\d+)\s+"),
        "graphicsPssKiB": number(r"^\s*Graphics:\s*(\d+)\s*$"),
    }


def collect(args: argparse.Namespace) -> None:
    if not PACKAGE.fullmatch(args.package):
        raise ValueError("Invalid Android package name")
    if not 1 <= args.duration <= 3600 or not .5 <= args.interval <= 60:
        raise ValueError("duration must be 1..3600 seconds; interval .5..60 seconds")
    # Read-only collection. No app start/reset, model download or user data access.
    qemu = adb(args, "shell", "getprop", "ro.kernel.qemu")
    fingerprint = adb(args, "shell", "getprop", "ro.build.fingerprint")
    hardware = adb(args, "shell", "getprop", "ro.hardware")
    device_kind = "emulator" if qemu == "1" or "generic" in fingerprint or hardware in ("ranchu", "goldfish") else "physical"
    pid = adb(args, "shell", "pidof", args.package)
    if not pid:
        raise ValueError("App is not running; start the requested test scenario before collecting")
    model = adb(args, "shell", "getprop", "ro.product.model")
    api = adb(args, "shell", "getprop", "ro.build.version.sdk")
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    started = time.perf_counter()
    try:
        while True:
            elapsed = time.perf_counter() - started
            if records and elapsed >= args.duration:
                break
            current_pid = adb(args, "shell", "pidof", args.package)
            if current_pid != pid:
                raise RuntimeError("App process changed during sampling; observation is incomplete")
            raw = adb(args, "shell", "dumpsys", "meminfo", args.package)
            measured = parse_memory(raw)
            if measured["pssKiB"] is None:
                raise RuntimeError("Cannot parse TOTAL PSS; keep raw evidence and update parser")
            filename = f"meminfo-{len(records):04d}.txt"
            (args.output / filename).write_text(raw + "\n", encoding="utf-8")
            records.append({"elapsedSeconds": elapsed, **measured, "raw": filename})
            delay = min(args.interval, args.duration - (time.perf_counter() - started))
            if delay > 0:
                time.sleep(delay)
        gfx = adb(args, "shell", "dumpsys", "gfxinfo", args.package, "framestats")
        (args.output / "gfxinfo-framestats.txt").write_text(gfx + "\n", encoding="utf-8")
        report = {
            "schemaVersion": 1, "status": "complete", "deviceKind": device_kind,
            "collectedAtUtc": datetime.now(timezone.utc).isoformat(),
            "device": {"model": model, "api": api, "fingerprint": fingerprint, "hardware": hardware},
            "package": args.package, "pid": pid, "scope": "app process only; no WebView in native editor",
            "phase": args.phase, "pageDigest": args.page_digest, "workload": args.workload,
            "path": args.path, "temperatureState": args.temperature_state,
            "warmState": args.warm_state, "samples": records,
            "peakPssKiB": max(r["pssKiB"] for r in records),
            "recognitionTiming": "not measured by meminfo sampling",
            "physicalAcceptance": "unassessed",
        }
        write_json(args.output / "memory.json", report)
        print(json.dumps({"deviceKind": device_kind, "samples": len(records), "peakPssKiB": report["peakPssKiB"], "output": str(args.output)}))
    except Exception as error:
        write_json(args.output / "memory.json", {
            "schemaVersion": 1, "status": "failed", "deviceKind": device_kind,
            "samples": records, "error": str(error), "physicalAcceptance": "unassessed",
        })
        raise


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    sorted_values = sorted(values)
    position = (len(values) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (position - lower)


def summarize(args: argparse.Namespace) -> None:
    data = json.loads(args.input.read_text(encoding="utf-8-sig"))
    records = data if isinstance(data, list) else data.get("runs")
    if not isinstance(records, list) or not records:
        raise ValueError("Input requires recorded runs")
    groups: dict[tuple, list[dict]] = {}
    for run in records:
        if run.get("deviceKind") not in ("physical", "emulator"):
            raise ValueError("Each run must declare observed deviceKind")
        if run.get("path") not in PATHS or run.get("workload") not in WORKLOADS:
            raise ValueError("Each run requires B0-manual/B0-auto/B1/B2/B3 and W0-W3")
        if not run.get("deviceId") or not run.get("buildSha") or not run.get("inputDigest"):
            raise ValueError("Each run needs device/build/input provenance")
        if run.get("state") not in ("cold", "warm") or run.get("status") not in ("complete", "partial", "failed", "cancelled"):
            raise ValueError("Each run requires observed state and status")
        # Cache, mixed-language policy and different timing scopes are separate
        # experiments. Combining them can make a misleading aggregate speedup.
        execution = run.get("execution", {})
        config = {name: execution.get(name, "unspecified") for name in ("feature", "policy", "context", "compression", "cacheMode")}
        config["measurementScope"] = run.get("measurementScope", "unspecified")
        config["inputKind"] = run.get("inputKind", "unspecified")
        key = (run["deviceId"], run["buildSha"], run["deviceKind"], run["path"], run["workload"], run["state"],
               json.dumps(config, sort_keys=True))
        groups.setdefault(key, []).append(run)
    output = []
    for key, runs in groups.items():
        eligible = [r for r in runs if r["status"] != "cancelled"]
        timings = []
        for run in eligible:
            elapsed = run.get("totalMs")
            if not isinstance(elapsed, (int, float)) or not math.isfinite(elapsed) or elapsed < 0:
                raise ValueError("Every non-cancelled run, including failures, needs measured totalMs")
            timings.append(elapsed)
        count = len(timings)
        inputs = len({r["inputDigest"] for r in eligible})
        output.append({
            "deviceId": key[0], "buildSha": key[1], "deviceKind": key[2],
            "path": key[3], "workload": key[4], "state": key[5],
            "configuration": json.loads(key[6]),
            "runs": len(runs), "nonCancelledRuns": count, "distinctInputs": inputs,
            "failures": sum(r["status"] == "failed" for r in eligible),
            "partial": sum(r["status"] == "partial" for r in eligible),
            "cancelled": len(runs) - count,
            "p50Ms": statistics.median(timings) if timings else None,
            "p95Ms": percentile(timings, .95),
            "p95Evidence": "comparison sample minimum reached" if count >= 100 and inputs > 1 else "exploratory: needs >=100 runs and diverse inputs",
            "physicalAcceptance": "unassessed",
        })
    write_json(args.output, {"schemaVersion": 1, "groups": output})
    print(json.dumps({"groups": len(output), "output": str(args.output)}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="command", required=True)
    create = actions.add_parser("generate", help="Generate geometric load fixtures; not handwriting truth")
    create.add_argument("--workload", choices=WORKLOADS, required=True)
    create.add_argument("--seed", type=int, default=77)
    create.add_argument("--output", type=pathlib.Path, required=True)
    create.set_defaults(run=generate)
    execute = actions.add_parser("replay", help="Explicit private input replay in an isolated installed debug app")
    execute.add_argument("--adb", default="adb")
    execute.add_argument("--device", required=True)
    execute.add_argument("--package", default="com.inkforge.note4.debug")
    execute.add_argument("--input", type=pathlib.Path, required=True)
    execute.add_argument("--path", choices=PATHS, required=True)
    execute.add_argument("--workload", choices=WORKLOADS, required=True)
    execute.add_argument("--policy", choices=("ko-primary", "en-primary", "mixed-review", "ja", "zh", "pt"), default="ko-primary")
    execute.add_argument("--feature", choices=("policy", "cache"), default="policy")
    execute.add_argument("--state", choices=("cold", "warm"), required=True)
    execute.add_argument("--timeout", type=float, default=900)
    execute.add_argument("--require-complete", action="store_true")
    execute.add_argument("--output", type=pathlib.Path, required=True)
    execute.set_defaults(run=replay)
    measure = actions.add_parser("collect", help="Read-only actual adb PSS/frame evidence")
    measure.add_argument("--adb", default="adb")
    measure.add_argument("--device", required=True)
    measure.add_argument("--package", default="com.inkforge.note4.debug")
    measure.add_argument("--duration", type=float, default=10)
    measure.add_argument("--interval", type=float, default=1)
    measure.add_argument("--phase", choices=("ocr-off", "ocr-on"), required=True)
    measure.add_argument("--page-digest", required=True)
    measure.add_argument("--workload", choices=WORKLOADS, required=True)
    measure.add_argument("--path", choices=PATHS, required=True)
    measure.add_argument("--temperature-state", required=True)
    measure.add_argument("--warm-state", choices=("cold", "warm"), required=True)
    measure.add_argument("--output", type=pathlib.Path, required=True)
    measure.set_defaults(run=collect)
    summary = actions.add_parser("summarize", help="Summarize consent-exported actual replay timings")
    summary.add_argument("input", type=pathlib.Path)
    summary.add_argument("--output", type=pathlib.Path, required=True)
    summary.set_defaults(run=summarize)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
