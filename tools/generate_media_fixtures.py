#!/usr/bin/env python3
"""Generate synthetic codec/container checks with installed ffmpeg/ffprobe.

These tones test file compatibility, not microphones or recording quality.
Android reference: https://developer.android.com/media/platform/supported-formats
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "build") or output == ROOT / "build":
        raise ValueError("Write generated fixtures beneath repository/build")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Use a new directory; existing fixtures are preserved")
    ffmpeg, ffprobe = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        raise ValueError("Installed ffmpeg and ffprobe are required; this tool does not install them")
    output.mkdir(parents=True, exist_ok=True)
    version = subprocess.run([ffmpeg, "-version"], capture_output=True, text=True, check=True).stdout.splitlines()[0]
    files = {}
    for name, encoder, codec, mime, frequency in (
        ("aac.m4a", "aac", "aac", "audio/mp4", 880),
        ("vorbis.ogg", "libvorbis", "vorbis", "audio/ogg", 660),
        ("opus.ogg", "libopus", "opus", "audio/ogg", 550),
        ("vorbis.webm", "libvorbis", "vorbis", "audio/webm", 440),
        ("opus.webm", "libopus", "opus", "audio/webm", 330),
    ):
        path = output / name
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-n",
                        "-f", "lavfi", "-i", f"sine=frequency={frequency}:sample_rate=48000:duration=1.5",
                        "-c:a", encoder, *(["-b:a", "96k"] if codec == "aac" else []), str(path)],
                       capture_output=True, check=True, timeout=30)
        probe = json.loads(subprocess.run([ffprobe, "-v", "error", "-show_streams", "-show_format",
                                          "-of", "json", str(path)], capture_output=True,
                                         text=True, check=True, timeout=30).stdout)
        streams = probe["streams"]
        assert len(streams) == 1 and streams[0]["codec_type"] == "audio" and streams[0]["codec_name"] == codec
        assert 1.0 <= float(probe["format"]["duration"]) <= 2.0
        files[name] = {"mime": mime, "codec": codec, "format": probe["format"]["format_name"],
                       "durationSeconds": float(probe["format"]["duration"])}
    shutil.copyfile(output / "aac.m4a", output / "legacy-aac.bin")
    files["legacy-aac.bin"] = {"mime": "application/octet-stream", "codec": "aac",
                               "purpose": "Legacy extension loss; decoder must inspect actual bytes"}
    (output / "malformed.bin").write_bytes(b"BADNOTE synthetic malformed audio; no valid container\n")
    files["malformed.bin"] = {"mime": "application/octet-stream", "codec": None,
                              "purpose": "Explicit async preparation error and recovery"}
    for name, item in files.items():
        raw = (output / name).read_bytes()
        item.update(size=len(raw), sha256=hashlib.sha256(raw).hexdigest())
    manifest = {"schemaVersion": 1, "provenance": {"kind": "synthetic", "purpose": "Native media compatibility"},
                "generator": {"version": version, "sourceSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
                "files": files, "nativePlayback": "not yet measured", "physicalMicrophoneQuality": "unmeasured"}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(files), "provenance": "synthetic", "output": str(output)}))


if __name__ == "__main__":
    main()
