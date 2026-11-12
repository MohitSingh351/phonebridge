#!/usr/bin/env python3
"""Extract conformance fixtures from protocol/PROTOCOL.md.

Blocks tagged ```json fixture=<path>.json are written verbatim (normalised JSON) to fixtures/<path>.json.
Blocks tagged ```json frame=<path> describe a binary chunk frame; we write <path>.bin (encoded frame)
and <path>.json (the expected decoded header fields incl. length and crc32).

Usage:
  python3 protocol/tools/extract_fixtures.py          # (re)generate fixtures
  python3 protocol/tools/extract_fixtures.py --check  # exit 1 if fixtures are stale (use in CI / pre-commit)
"""
from __future__ import annotations

import json
import re
import struct
import sys
import uuid
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "PROTOCOL.md"
OUT = ROOT / "fixtures"

BLOCK = re.compile(r"^```json (fixture|frame)=(\S+)\n(.*?)^```", re.M | re.S)

MAGIC = b"PB"
FRAME_VERSION = 1
HEADER = struct.Struct(">2sBB16sQII")  # magic, version, kind, transferId, offset, length, crc32 = 36 bytes
assert HEADER.size == 36


def encode_frame(spec: dict) -> tuple[bytes, dict]:
    payload = bytes.fromhex(spec["payloadHex"])
    crc = zlib.crc32(payload) & 0xFFFFFFFF
    tid = uuid.UUID(spec["transferId"]).bytes
    frame = HEADER.pack(MAGIC, FRAME_VERSION, spec["kind"], tid, spec["offset"], len(payload), crc) + payload
    expected = {
        "version": FRAME_VERSION,
        "kind": spec["kind"],
        "transferId": spec["transferId"],
        "offset": spec["offset"],
        "length": len(payload),
        "crc32": crc,
        "payloadHex": spec["payloadHex"],
    }
    return frame, expected


def build() -> dict[Path, bytes]:
    files: dict[Path, bytes] = {}
    text = SPEC.read_text(encoding="utf-8")
    for kind, rel, body in BLOCK.findall(text):
        data = json.loads(body)  # fail loudly on invalid JSON in the spec
        if kind == "fixture":
            files[OUT / rel] = (json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        else:
            frame, expected = encode_frame(data)
            files[OUT / f"{rel}.bin"] = frame
            files[OUT / f"{rel}.json"] = (json.dumps(expected, indent=2) + "\n").encode()
    if not files:
        sys.exit("no fixtures found in PROTOCOL.md")
    return files


def main() -> int:
    check = "--check" in sys.argv
    files = build()
    existing = {p for p in OUT.rglob("*") if p.is_file() and p.name != ".gitkeep"}
    stale = [p for p, b in files.items() if not p.exists() or p.read_bytes() != b]
    orphans = sorted(existing - set(files))
    if check:
        for p in stale:
            print(f"stale: {p.relative_to(ROOT)}")
        for p in orphans:
            print(f"orphan: {p.relative_to(ROOT)}")
        return 1 if stale or orphans else 0
    for p, b in files.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b)
    for p in orphans:
        p.unlink()
    print(f"wrote {len(files)} fixtures, removed {len(orphans)} orphans")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
