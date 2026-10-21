#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""NAS 守护进程：飞牛远程访问登录失败 → 用米家 App 点插座断电重启。

    .mijia/venv314/bin/python .mijia/watchdog.py                # 正式守护
    .mijia/venv314/bin/python .mijia/watchdog.py --dry-run      # 只检测，绝不断电
    .mijia/venv314/bin/python .mijia/watchdog.py --once         # 只跑一轮检测
    .mijia/venv314/bin/python .mijia/watchdog.py --test-plug    # 只测插座链路（会真断电！）

判定：连续 fail_threshold 次登录探测不成功 => 判定 NAS 已挂 => 断电重启插座。
保护：重启后有冷却期；每天重启次数有上限；冷却期内只告警不动手。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import signal
import sys
import time
from datetime import datetime

BASE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))

import fn_login  # noqa: E402
import mijia_plug  # noqa: E402

CONFIG = {
    "interval": 180,           # 两轮检测间隔（秒）；单次探测本身约 35s
    "fail_threshold": 2,       # 连续失败多少次才动手
    "off_seconds": 10.0,       # 插座断电时长
    "cooldown": 900,           # 重启后冷却（秒），留给 NAS 开机
    "max_restarts_per_day": 6, # 每天最多重启几次，防止插座/nas 陷入循环
    "login_timeout_ms": 60_000,
}

LOG = BASE / "watchdog.log"
STATE = BASE / "watchdog_state.json"

_stop = False


def log(msg: str) -> None:
    line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    print(line, flush=True)
    try:
        with LOG.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def load_state() -> dict:
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except Exception:
        return {"last_restart": 0.0, "day": "", "restarts_today": 0}


def save_state(s: dict) -> None:
    try:
        STATE.write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception:
        pass


def _on_signal(signum, frame):  # noqa: ARG001
    global _stop
    _stop = True
    log(f"收到信号 {signum}，准备退出…")


def check_once() -> dict:
    r = fn_login.attempt(headless=True, do_login=True, timeout_ms=CONFIG["login_timeout_ms"])
    return r


def run(dry_run: bool = False, once: bool = False) -> int:
    global _stop
    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)

    state = load_state()
    fails = 0
    log("=" * 72)
    log(f"守护启动 dry_run={dry_run} 间隔={CONFIG['interval']}s 阈值={CONFIG['fail_threshold']} "
        f"断电={CONFIG['off_seconds']}s 冷却={CONFIG['cooldown']}s")

    while not _stop:
        t0 = time.time()
        try:
            r = check_once()
        except Exception as exc:  # noqa: BLE001
            r = {"ok": False, "stage": "exception", "detail": f"{type(exc).__name__}: {exc}", "elapsed": 0}

        if r.get("ok"):
            if fails:
                log(f"恢复正常 (此前连续失败 {fails} 次)")
            fails = 0
            log(f"OK   {r.get('stage')}  {r.get('elapsed')}s  {r.get('url','')}")
        else:
            fails += 1
            log(f"FAIL {fails}/{CONFIG['fail_threshold']}  stage={r.get('stage')}  "
                f"detail={r.get('detail')}  {r.get('elapsed')}s")

            if fails >= CONFIG["fail_threshold"]:
                now = time.time()
                today = datetime.now().strftime("%Y-%m-%d")
                if state.get("day") != today:
                    state["day"] = today
                    state["restarts_today"] = 0

                left = CONFIG["cooldown"] - (now - state.get("last_restart", 0))
                if left > 0:
                    log(f"处于冷却期，跳过重启（还剩 {left:.0f}s）")
                elif state.get("restarts_today", 0) >= CONFIG["max_restarts_per_day"]:
                    log(f"今日重启次数已达上限 {CONFIG['max_restarts_per_day']}，跳过（保护 NAS）")
                elif dry_run:
                    log("[dry-run] 本应重启插座，已跳过")
                    fails = 0
                else:
                    log("触发插座断电重启 …")
                    try:
                        res = mijia_plug.power_cycle(CONFIG["off_seconds"])
                    except Exception as exc:  # noqa: BLE001
                        res = {"error": f"{type(exc).__name__}: {exc}"}
                    log(f"插座操作结果: {res}")
                    if isinstance(res, dict) and res.get("off") and res.get("on"):
                        state["last_restart"] = time.time()
                        state["restarts_today"] = state.get("restarts_today", 0) + 1
                        save_state(state)
                        log(f"重启完成（今日第 {state['restarts_today']} 次），冷却 {CONFIG['cooldown']}s")
                        fails = 0
                    else:
                        log("插座操作未成功，保持失败计数")

        if once or _stop:
            break
        elapsed = time.time() - t0
        wait = max(5.0, CONFIG["interval"] - elapsed)
        time.sleep(wait)

    log("守护退出")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只检测，不断电")
    ap.add_argument("--once", action="store_true", help="只跑一轮")
    ap.add_argument("--test-plug", action="store_true", help="只测插座断电重启链路（真断电）")
    args = ap.parse_args()

    if args.test_plug:
        log("=== 手动测试插座断电重启链路 ===")
        st = mijia_plug.read_state()
        log(f"当前状态: {st}")
        res = mijia_plug.power_cycle(CONFIG["off_seconds"])
        log(f"结果: {res}")
        return 0 if (res.get("off") and res.get("on")) else 1

    return run(dry_run=args.dry_run, once=args.once)


if __name__ == "__main__":
    sys.exit(main())
