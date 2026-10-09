#!/usr/bin/env python3
"""Evaluate consent-exported OCR fixtures without repairing reading order or failures.

Input: JSON/JSONL samples with schemaVersion, sampleId, pageDigest, provenance,
truthRegions, readingOrder and result. No recognition is simulated by this tool.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import unicodedata
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class Edits:
    reference: int
    substitutions: int
    deletions: int
    insertions: int

    @property
    def distance(self) -> int:
        return self.substitutions + self.deletions + self.insertions

    def report(self) -> dict[str, Any]:
        return {
            **asdict(self),
            "cer": self.distance / self.reference if self.reference else None,
            "referenceRetention": (
                (self.reference - self.substitutions - self.deletions) / self.reference
                if self.reference else None
            ),
        }


def normalize(text: str, *, jamo: bool = False, spaces: bool = True) -> str:
    """NFC is primary; NFD decomposes Hangul without compatibility folding."""
    value = unicodedata.normalize("NFC", text)
    if jamo:
        value = unicodedata.normalize("NFD", value)
    return value if spaces else "".join(c for c in value if not c.isspace())


def edits(reference: str, prediction: str) -> Edits:
    """Linear-memory Levenshtein counts; ties: match, substitute, delete, insert."""
    # Counts travel with each cell, so the declared tie rule is reproducible.
    previous = [(j, 0, 0, j) for j in range(len(prediction) + 1)]
    for i, expected in enumerate(reference, 1):
        current = [(i, 0, i, 0)]
        for j, actual in enumerate(prediction, 1):
            if expected == actual:
                current.append(previous[j - 1])
                continue
            cost, sub, delete, insert = previous[j - 1]
            substitution = (cost + 1, sub + 1, delete, insert)
            cost, sub, delete, insert = previous[j]
            deletion = (cost + 1, sub, delete + 1, insert)
            cost, sub, delete, insert = current[j - 1]
            insertion = (cost + 1, sub, delete, insert + 1)
            current.append(min((substitution, deletion, insertion), key=lambda cell: cell[0]))
        previous = current
    _, substitutions, deletions, insertions = previous[-1]
    return Edits(len(reference), substitutions, deletions, insertions)


def text_of(region: dict[str, Any], mode: str) -> str:
    # Explicit empty correction is meaningful and must not fall back to raw text.
    keys = {
        "raw": ("rawText",),
        "selected": ("selectedText", "rawText"),
        "corrected": ("correctedText", "selectedText", "rawText"),
    }[mode]
    for key in keys:
        if region.get(key) is not None:
            if not isinstance(region[key], str):
                raise ValueError(f"{key} must be a string or null")
            return region[key]
    return ""


def _ids(region: dict[str, Any]) -> tuple[str, ...]:
    values = region.get("strokeIds", region.get("sourceStrokeIds", []))
    if not isinstance(values, list) or any(not isinstance(x, str) or not x for x in values):
        raise ValueError("strokeIds must contain non-empty string IDs")
    return tuple(values)


def validate_truth(sample: dict[str, Any]) -> list[dict[str, Any]]:
    if sample.get("schemaVersion") != 1 or not sample.get("sampleId") or not sample.get("pageDigest"):
        raise ValueError("sample requires schemaVersion=1, sampleId and pageDigest")
    provenance = sample.get("provenance", {})
    if provenance.get("kind") not in ("real", "synthetic"):
        raise ValueError("provenance.kind must explicitly be real or synthetic")
    if provenance["kind"] == "real" and any(not provenance.get(key) for key in (
        "writerId", "sessionId", "deviceId", "language"
    )):
        raise ValueError("real samples require writer/session/device/language provenance")
    regions = sample.get("truthRegions")
    if not isinstance(regions, list):
        raise ValueError("truthRegions must be an array")
    by_id: dict[str, dict[str, Any]] = {}
    seen: set[str] = set()
    for region in regions:
        if not isinstance(region, dict) or not isinstance(region.get("id"), str) or not region["id"]:
            raise ValueError("truth region requires a non-empty ID")
        if region["id"] in by_id:
            raise ValueError("duplicate truth region ID")
        if region.get("role") not in ("text", "nonText", "unreadable"):
            raise ValueError("truth role must be text, nonText or unreadable")
        ids = _ids(region)
        if not ids or len(ids) != len(set(ids)) or seen.intersection(ids):
            raise ValueError("truth strokes must have exactly one disposition")
        seen.update(ids)
        if region["role"] == "text" and not isinstance(region.get("text"), str):
            raise ValueError("text truth region requires transcribed text")
        by_id[region["id"]] = region
    # Reject an omitted order rather than sorting the model output geometrically.
    order = sample.get("readingOrder")
    if not isinstance(order, list) or len(order) != len(set(order)) or set(order) != set(by_id):
        raise ValueError("readingOrder must list each truth region exactly once")
    if "validStrokeIds" in sample:
        if set(sample["validStrokeIds"]) != seen or len(sample["validStrokeIds"]) != len(seen):
            raise ValueError("truth dispositions must cover every valid source stroke")
    return [by_id[key] for key in order]


def evaluate(sample: dict[str, Any], mode: str = "selected") -> dict[str, Any]:
    truth = validate_truth(sample)
    result = sample.get("result", {})
    if not isinstance(result, dict) or result.get("status") not in ("complete", "partial", "failed", "cancelled"):
        raise ValueError("result requires an explicit status")
    status = result["status"]
    stale = result.get("pageDigest", sample["pageDigest"]) != sample["pageDigest"]
    predictions = result.get("regions", [])
    if not isinstance(predictions, list):
        raise ValueError("result.regions must be an array")
    # array order is the engine's reading order, not a post-hoc geometry alignment.
    # Explicit readingOrder may be supplied by an adapter instead.
    if "readingOrder" in result:
        by_id = {r["id"]: r for r in predictions}
        order = result["readingOrder"]
        if len(by_id) != len(predictions) or len(order) != len(set(order)) or set(order) != set(by_id):
            raise ValueError("invalid prediction readingOrder")
        predictions = [by_id[key] for key in order]
    source_ids = {stroke for region in truth for stroke in _ids(region)}
    counts = Counter(stroke for region in predictions for stroke in _ids(region))
    dispositions = list(result.get("nonTextPreserved", [])) + list(result.get("unresolved", []))
    disposition_counts = Counter(dispositions)
    preserved = set(dispositions)
    represented = set(counts) | preserved
    duplicated = sorted(key for key in represented if counts[key] + disposition_counts[key] > 1)
    unknown = sorted(represented - source_ids)
    missing = sorted(source_ids - represented)
    unreadable_ids = {stroke for r in truth if r["role"] == "unreadable" for stroke in _ids(r)}
    unresolved = set(result.get("unresolved", []))
    successful = ("complete", "recognized", "ok", "cached")
    def usable(region: dict[str, Any]) -> bool:
        return region.get("status", "complete") in successful
    # failed and stale pages contribute full reference deletions. Cancellation is
    # reported separately and excluded only as an explicit user action.
    active = [] if status in ("failed", "cancelled") or stale else predictions
    predicted_text = "\n".join(text_of(r, mode) for r in active if usable(r))
    text_truth = [r for r in truth if r["role"] == "text"]
    reference = "\n".join(r["text"] for r in text_truth)
    metrics = {
        "nfc": edits(normalize(reference), normalize(predicted_text)).report(),
        "nfcNoWhitespace": edits(normalize(reference, spaces=False), normalize(predicted_text, spaces=False)).report(),
        "jamo": edits(normalize(reference, jamo=True), normalize(predicted_text, jamo=True)).report(),
    }
    candidates: dict[frozenset[str], list[dict[str, Any]]] = defaultdict(list)
    for region in active:
        candidates[frozenset(_ids(region))].append(region)
    exact_lines = 0
    matched_lines = 0
    for expected in text_truth:
        matching = candidates[frozenset(_ids(expected))]
        if len(matching) == 1 and usable(matching[0]) and not any(s in duplicated for s in _ids(expected)):
            matched_lines += 1
            exact_lines += normalize(expected["text"]) == normalize(text_of(matching[0], mode))
    return {
        "sampleId": sample["sampleId"], "pageDigest": sample["pageDigest"],
        "provenanceKind": sample["provenance"]["kind"],
        "writerId": sample["provenance"].get("writerId"),
        "sessionId": sample["provenance"].get("sessionId"),
        "language": sample["provenance"].get("language", "unspecified"),
        "path": sample.get("path", "unspecified"), "mode": mode,
        "status": status, "stale": stale, "cancelled": status == "cancelled",
        "failed": status == "failed" or stale,
        "partial": status == "partial", "emptyPrediction": not predicted_text,
        "emptyTruthFalsePositive": not reference and bool(predicted_text),
        "pageExact": normalize(reference) == normalize(predicted_text) and not stale and status != "failed",
        "metrics": metrics, "truthLines": len(text_truth),
        "matchedLines": matched_lines, "exactLines": exact_lines,
        "lineExactRate": exact_lines / len(text_truth) if text_truth else None,
        "missingStrokeIds": missing, "duplicatedStrokeIds": duplicated, "unknownStrokeIds": unknown,
        "strokeConservation": not (missing or duplicated or unknown),
        "unreadableStrokes": len(unreadable_ids),
        "unresolvedStrokes": len(unresolved | {
            stroke for r in predictions if r.get("status") == "unresolved" for stroke in _ids(r)
        }), "validStrokes": len(source_ids),
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    included = [row for row in rows if not row["cancelled"]]
    metrics = {}
    for name in ("nfc", "nfcNoWhitespace", "jamo"):
        counts = [row["metrics"][name] for row in included]
        metrics[name] = Edits(*(sum(c[key] for c in counts) for key in (
            "reference", "substitutions", "deletions", "insertions"
        ))).report()
    lines = sum(r["truthLines"] for r in included)
    exact = sum(r["exactLines"] for r in included)
    empty = [r for r in included if r["metrics"]["nfc"]["reference"] == 0]
    return {
        "pages": len(rows), "evaluatedPages": len(included),
        "distinctWriters": len({r["writerId"] for r in rows if r.get("writerId")}),
        "distinctWriterSessions": len({(r["writerId"], r["sessionId"]) for r in rows if r.get("writerId") and r.get("sessionId")}),
        "cancelledPages": len(rows) - len(included),
        "failedPages": sum(r["failed"] for r in included),
        "partialPages": sum(r["partial"] for r in included),
        "failureRate": sum(r["failed"] for r in included) / len(included) if included else None,
        "pageExactRate": sum(r["pageExact"] for r in included) / len(included) if included else None,
        "truthLines": lines, "exactLines": exact, "lineExactRate": exact / lines if lines else None,
        "emptyTruthPages": len(empty),
        "emptyTruthFalsePositiveRate": sum(r["emptyTruthFalsePositive"] for r in empty) / len(empty) if empty else None,
        "strokeConservationFailures": sum(not r["strokeConservation"] for r in included),
        "validStrokes": sum(r["validStrokes"] for r in included),
        "unresolvedStrokes": sum(r["unresolvedStrokes"] for r in included),
        "metrics": metrics,
    }


def load_samples(path: pathlib.Path, *, allow_empty: bool = False) -> list[dict[str, Any]]:
    raw = path.read_text(encoding="utf-8-sig")
    if path.suffix == ".jsonl":
        samples = [json.loads(line) for line in raw.splitlines() if line.strip()]
    else:
        data = json.loads(raw)
        samples = data if isinstance(data, list) else data.get("samples", [data])
    if (not samples and not allow_empty) or any(not isinstance(x, dict) for x in samples):
        raise ValueError("input requires at least one sample object")
    identities = [x.get("sampleId", x.get("pageId")) for x in samples]
    if any(not identity for identity in identities) or len(set(identities)) != len(samples):
        raise ValueError("missing or duplicate sampleId/pageId")
    return samples


def attach_results(samples: list[dict[str, Any]], results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Join explicit diagnostic outputs to annotations; missing output is failure."""
    by_id = {r.get("sampleId", r.get("pageId")): r for r in results}
    source_ids = {s.get("pageId", s["sampleId"]) for s in samples}
    unexpected = set(by_id) - source_ids
    if unexpected:
        raise ValueError("Result IDs do not match annotations")
    joined = []
    for sample in samples:
        value = dict(sample)
        source = by_id.get(sample.get("pageId", sample["sampleId"]))
        if source is None:
            value["result"] = {"status": "failed", "regions": [], "reason": "missingResult"}
        else:
            result = dict(source.get("result", source))
            result.setdefault("pageDigest", source.get("pageDigest"))
            if not result.get("pageDigest"):
                raise ValueError("Diagnostic result requires its actual pageDigest")
            value["result"] = result
            value["path"] = source.get("path", value.get("path", "unspecified"))
        joined.append(value)
    return joined


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument("--mode", choices=("raw", "selected", "corrected"), default="selected")
    parser.add_argument("--results", type=pathlib.Path, help="Join runtime diagnostic results by sampleId/pageId")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    samples = load_samples(args.input)
    if args.results:
        samples = attach_results(samples, load_samples(args.results, allow_empty=True))
    rows = [evaluate(sample, args.mode) for sample in samples]
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        groups[f'{row["provenanceKind"]}/{row["language"]}/{row["path"]}'].append(row)
    report = {
        "schemaVersion": 1,
        "alignmentTieRule": "match, substitute, delete, insert",
        "normalization": "NFC; case/digits/punctuation/whitespace preserved",
        "cancelledPolicy": "explicit user cancellation reported separately",
        "groups": {key: aggregate(value) for key, value in sorted(groups.items())},
        "samples": rows,
        "qualityAcceptance": "unassessed; synthetic results are tool regressions only",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pages": len(rows), "groups": list(report["groups"]), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
