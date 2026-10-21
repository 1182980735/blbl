#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通过 adb 操作 MuMu 模拟器里的米家 App，控制「智能计电量插座M1」。

命令行：
    .mijia/venv314/bin/python .mijia/mijia_plug.py state          # 只读当前状态
    .mijia/venv314/bin/python .mijia/mijia_plug.py off            # 关电
    .mijia/venv314/bin/python .mijia/mijia_plug.py on             # 通电
    .mijia/venv314/bin/python .mijia/mijia_plug.py cycle --seconds 8   # 断电重启

UI 事实（实测 dump）：
  首页卡片 content-desc = "智能计电量插座M1客厅已开启"  bounds=[162,268][520,635]
  设备页按钮文本 "关机"(当前开机) / "开机"(当前关机)     bounds=[893,816][996,1018]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

DEVICE = "127.0.0.1:16416"
ADB = ["adb", "-s", DEVICE]
PLUG_KEY = "智能计电量插座M1"
MAIN_ACTIVITY = "com.xiaomi.smarthome/.SmartHomeMainActivity"
DUMP_DIR = Path(__file__).resolve().parent / "dump"
LOCAL_XML = DUMP_DIR / "ui_live.xml"
REMOTE_XML = "/sdcard/ui_live.xml"


def sh(args: list[str], timeout: int = 40) -> str:
    p = subprocess.run(ADB + args, capture_output=True, text=True, timeout=timeout)
    return (p.stdout or "") + (p.stderr or "")


def center(bounds: str) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def dump(retries: int = 3) -> str:
    """把当前界面层级 dump 到本地并返回 XML 文本。"""
    DUMP_DIR.mkdir(parents=True, exist_ok=True)
    for i in range(retries):
        sh(["shell", "rm", "-f", REMOTE_XML], timeout=20)
        sh(["shell", "uiautomator", "dump", REMOTE_XML], timeout=45)
        sh(["pull", REMOTE_XML, str(LOCAL_XML)], timeout=30)
        if LOCAL_XML.exists():
            txt = LOCAL_XML.read_text(encoding="utf-8", errors="replace")
            if "<hierarchy" in txt:
                return txt
        time.sleep(1.5)
    raise RuntimeError("uiautomator dump 连续失败，模拟器可能卡住或息屏")


def home_state() -> str | None:
    """从首页卡片 content-desc 快速读状态：'on' / 'off' / 'offline' / None。

    卡片 desc 形如：智能计电量插座M1客厅已开启 / …已关闭 / …设备离线
    """
    xml = dump()
    for e in tree(xml):
        d = e.get("content-desc") or ""
        if PLUG_KEY in d:
            if "离线" in d:
                return "offline"
            if "已开启" in d:
                return "on"
            if "已关闭" in d:
                return "off"
    return None


def tree(xml: str):
    return list(ET.fromstring(xml).iter())


def texts(xml: str) -> list[str]:
    out = []
    for e in tree(xml):
        t = (e.get("text") or "").strip()
        if t:
            out.append(t)
    return out


def on_home(xml: str) -> bool:
    t = texts(xml)
    return any(x in t for x in ("米家", "智能", "产品", "商城", "我的"))


def device_state(xml: str) -> str | None:
    """设备页按钮显示 '关机' => 当前通电(on)；显示 '开机' => 当前断电(off)。"""
    t = texts(xml)
    if "关机" in t:
        return "on"
    if "开机" in t:
        return "off"
    return None


def button_bounds(xml: str) -> str | None:
    for e in tree(xml):
        if (e.get("text") or "").strip() in ("关机", "开机"):
            return e.get("bounds")
    return None


def plug_card(xml: str):
    for e in tree(xml):
        desc = e.get("content-desc") or ""
        if PLUG_KEY in desc and e.get("clickable") == "true":
            return e
    return None


def wake() -> None:
    sh(["shell", "input", "keyevent", "224"])  # WAKEUP
    time.sleep(0.8)
    sh(["shell", "input", "keyevent", "82"])  # MENU / 解锁
    time.sleep(0.8)


def open_app() -> None:
    sh(["shell", "am", "start", "-n", MAIN_ACTIVITY], timeout=30)
    time.sleep(5)


def go_home(max_back: int = 4) -> str:
    """确保回到米家首页，返回首页 XML。"""
    for i in range(max_back):
        xml = dump()
        if on_home(xml):
            return xml
        sh(["shell", "input", "keyevent", "4"])  # BACK
        time.sleep(2)
    raise RuntimeError("按键返回失败，回不到米家首页")


def open_device_page() -> str:
    """从首页点进插座设备页，返回设备页 XML。"""
    for attempt in range(2):
        xml = go_home()
        card = plug_card(xml)
        if card is None:
            raise RuntimeError(f"首页没找到「{PLUG_KEY}」卡片")
        x, y = center(card.get("bounds"))
        sh(["shell", "input", "tap", str(x), str(y)])
        time.sleep(7)
        page = dump()
        if device_state(page) is not None:
            return page
        time.sleep(3)
    raise RuntimeError("进入设备页后读不到开关按钮")


def read_state() -> str | None:
    """确认插座当前是通电还是断电。"""
    return device_state(open_device_page())


def set_state(target: str, verify_rounds: int = 4) -> bool:
    """target: 'on' 通电 / 'off' 断电，带点击后校验。"""
    if target not in ("on", "off"):
        raise ValueError(target)
    page = open_device_page()
    for _ in range(verify_rounds):
        cur = device_state(page)
        if cur == target:
            return True
        b = button_bounds(page)
        if b is None:
            raise RuntimeError("找不到开关按钮坐标")
        x, y = center(b)
        sh(["shell", "input", "tap", str(x), str(y)])
        time.sleep(4)
        page = dump()
        if device_state(page) == target:
            return True
    return device_state(page) == target


def power_cycle(off_seconds: float = 8.0) -> dict:
    """断电 -> 等待 -> 通电，返回每步结果。"""
    result = {"off": False, "waited": off_seconds, "on": False}
    result["off"] = set_state("off")
    if not result["off"]:
        return result
    time.sleep(off_seconds)
    result["on"] = set_state("on")
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["state", "on", "off", "cycle"])
    ap.add_argument("--seconds", type=float, default=8.0, help="cycle 的断电时长")
    args = ap.parse_args()

    wake()
    open_app()

    if args.action == "state":
        st = read_state()
        print({"on": "通电中(已开启)", "off": "已断电(已关闭)", None: "未知"}[st])
        return 0 if st else 1
    if args.action in ("on", "off"):
        ok = set_state(args.action)
        print(f"{args.action} -> {'成功' if ok else '失败'}")
        return 0 if ok else 1

    r = power_cycle(args.seconds)
    print(f"断电: {'成功' if r['off'] else '失败'} | 等待 {r['waited']}s | 通电: {'成功' if r['on'] else '失败'}")
    return 0 if (r["off"] and r["on"]) else 1


if __name__ == "__main__":
    sys.exit(main())
