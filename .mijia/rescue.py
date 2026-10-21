#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守候脚本：等米家插座恢复在线后，立刻把它切到通电。

场景：插座离线时云端指令下不去，只能等它自己（或人工）联网回来。
本脚本每 45 秒检查一次首页卡片状态：
    'on'      -> 已通电，退出
    'off'     -> 在线但断电，立刻点开机
    'offline' -> 继续等

    .mijia/venv314/bin/python .mijia/rescue.py --minutes 60
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import time
from datetime import datetime

BASE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))
import mijia_plug as mp  # noqa: E402

LOG = BASE / "rescue.log"


def log(msg: str) -> None:
    line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    print(line, flush=True)
    with LOG.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=60.0)
    ap.add_argument("--interval", type=float, default=45.0)
    args = ap.parse_args()

    deadline = time.time() + args.minutes * 60
    log(f"守候启动，最长 {args.minutes} 分钟，每 {args.interval}s 检查一次")
    mp.wake()
    mp.open_app()

    while time.time() < deadline:
        try:
            mp.go_home()
            st = mp.home_state()
        except Exception as exc:  # noqa: BLE001
            st = None
            log(f"读取状态失败：{type(exc).__name__}: {exc}")

        if st == "on":
            log("插座已通电 ✓")
            return 0
        if st == "off":
            log("插座在线但处于断电 → 尝试开机")
            try:
                if mp.set_state("on"):
                    log("开机成功 ✓")
                    return 0
                log("开机未成功，稍后重试")
            except Exception as exc:  # noqa: BLE001
                log(f"开机异常：{type(exc).__name__}: {exc}")
        elif st == "offline":
            log("插座仍离线，继续等待…")
        else:
            log(f"状态未知({st})，继续等待…")

        time.sleep(args.interval)

    log("守候超时退出，插座仍未恢复通电")
    return 1


if __name__ == "__main__":
    sys.exit(main())
