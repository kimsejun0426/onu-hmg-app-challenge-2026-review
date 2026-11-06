"""Fail if a final APK DEX still references either Pleos speech SDK logger.

Run after the R8 build, optionally with its mapping.txt. This checks all DEX type
and method tables, so it is deliberately stricter than looking for invoke opcodes.
It does not claim to inspect the separate vehicle Speech Service or its logs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
from zipfile import ZipFile

LOGGERS = {"ai.pleos.playground.stt.utils.Log", "ai.pleos.playground.tts.utils.Log"}


def descriptors(mapping: Path | None) -> set[str]:
    names = set(LOGGERS)
    if mapping is not None:
        for line in mapping.read_text(encoding="utf-8").splitlines():
            match = re.fullmatch(r"(\S+) -> (\S+):", line)
            if match and match[1] in LOGGERS:
                names.add(match[2])
    return {"L" + name.replace(".", "/") + ";" for name in names}


def dex_references(data: bytes, logger_types: set[str]) -> dict:
    if len(data) < 112 or not data.startswith(b"dex\n") or data[7] != 0:
        raise ValueError("Unsupported or truncated DEX header")
    if struct.unpack_from("<I", data, 40)[0] != 0x12345678:
        raise ValueError("Unsupported DEX byte order")

    def table(header: int, width: int) -> tuple[int, int]:
        size, offset = struct.unpack_from("<II", data, header)
        if offset > len(data) or offset + size * width > len(data):
            raise ValueError("DEX table extends beyond file")
        return size, offset

    def string(offset: int) -> str:
        # Skip the UTF-16-length ULEB128; SDK identifiers themselves are ASCII.
        for _ in range(5):
            byte = data[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.index(b"\0", offset)
        return data[offset:end].decode("utf-8", errors="replace")

    string_size, string_offset = table(56, 4)
    strings = [string(struct.unpack_from("<I", data, string_offset + i * 4)[0])
               for i in range(string_size)]
    type_size, type_offset = table(64, 4)
    types = [strings[struct.unpack_from("<I", data, type_offset + i * 4)[0]]
             for i in range(type_size)]
    method_size, method_offset = table(88, 8)
    methods = []
    for i in range(method_size):
        owner, _, name = struct.unpack_from("<HHI", data, method_offset + i * 8)
        if types[owner] in logger_types:
            methods.append({"owner": types[owner], "name": strings[name]})
    return {"type_references": sorted(set(types) & logger_types), "method_references": methods}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--mapping", type=Path)
    args = parser.parse_args()
    logger_types = descriptors(args.mapping)
    with ZipFile(args.apk) as archive:
        dex_files = sorted(name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
        if not dex_files:
            raise SystemExit("APK contains no classes DEX")
        checked = {name: dex_references(archive.read(name), logger_types) for name in dex_files}
    failed = any(result["type_references"] or result["method_references"] for result in checked.values())
    print(json.dumps({"apk": str(args.apk), "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                      "mapping": str(args.mapping) if args.mapping else None,
                      "sdk_loggers_absent": not failed, "dex": checked}, indent=2))
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
