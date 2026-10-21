#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在 MuMu 模拟器里以 root 执行命令的小包装。

    .mijia/venv314/bin/python .mijia/mumu_shell.py "ls -la /data/data"

MuMu 的 control run_cmd 通道实测以 uid=0 运行，所以不需要 su。
"""

from __future__ import annotations

import json
import subprocess
import sys

TOOL = "/Applications/MuMuPlayer.app/Contents/MacOS/mumutool"


def run(cmd: str, timeout: int = 120) -> str:
    p = subprocess.run(
        [TOOL, "control", "all", "--action", "run_cmd", "--cmd", cmd],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    try:
        data = json.loads(p.stdout)
        results = data["return"]["results"]
        outs = []
        for r in results:
            out = r.get("callback", {}).get("return", {}).get("output", "")
            outs.append(out)
        return "".join(outs)
    except Exception as exc:  # noqa: BLE001
        return f"[ERR] {exc}\nstdout={p.stdout[:800]}\nstderr={p.stderr[:400]}"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.stdout.write(run(" ".join(sys.argv[1:])))
