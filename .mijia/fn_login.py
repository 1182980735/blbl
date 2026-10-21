#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""飞牛 fnOS 远程访问 —— 登录探测模块。

流程（实测）：
    https://anhuihl.5ddd.com/  →302→  5ddd.com/anhuihl/  "正在连接..."
                               →10s→  anhuihl.5ddd.com/login   飞牛登录页
                               填 #username / #password → 点"登录"

命令行：
    .mijia/venv314/bin/python .mijia/fn_login.py            # 跑一次完整登录探测
    .mijia/venv314/bin/python .mijia/fn_login.py --no-login # 只探测登录页是否出现
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys
import time

BASE = pathlib.Path(__file__).resolve().parent
DUMP = BASE / "dump"
ENTRY = "https://anhuihl.5ddd.com/"

USERNAME = os.environ.get("FN_USER", "admin")
PASSWORD = os.environ.get("FN_PASS", "hl123456")

# 登录成功后不应再停留的路径
LOGIN_PATH = "/login"


def attempt(headless: bool = True, do_login: bool = True, timeout_ms: int = 60_000) -> dict:
    """返回 {ok, stage, url, detail, shots, posts, elapsed}。ok=True 表示 NAS 活着且登录成功。"""
    from playwright.sync_api import sync_playwright

    res: dict = {
        "ok": False, "stage": "start", "url": "", "detail": "",
        "posts": [], "console_errors": [], "elapsed": 0.0, "at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    t0 = time.time()
    DUMP.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=headless)
        ctx = browser.new_context(ignore_https_errors=True, locale="zh-CN")
        page = ctx.new_page()

        def on_request(r):
            if r.method == "POST" and ("login" in r.url or "auth" in r.url or "api" in r.url):
                res["posts"].append({
                    "url": r.url,
                    "post": (r.post_data or "")[:400],
                })

        page.on("request", on_request)
        page.on("pageerror", lambda e: res["console_errors"].append(str(e)[:200]))

        try:
            page.goto(ENTRY, wait_until="domcontentloaded", timeout=timeout_ms)
            res["stage"] = "portal"
            page.wait_for_url(f"**{LOGIN_PATH}", timeout=timeout_ms)
            res["stage"] = "login_page"
            page.wait_for_selector("#username", state="visible", timeout=45_000)
            res["url"] = page.url

            if not do_login:
                res["detail"] = "登录页已渲染（未提交凭证）"
                res["ok"] = True
                return res

            page.fill("#username", USERNAME)
            page.fill("#password", PASSWORD)
            res["stage"] = "filled"

            page.click("button:has-text('登录')")
            res["stage"] = "submitted"

            # 成功 = 回到管理地址(ENTRY) 且登录表单真的消失
            # 失败 = 仍在登录页 / 出现错误提示 / 超时
            for _ in range(30):
                page.wait_for_timeout(1000)
                cur = page.url
                if LOGIN_PATH not in cur:
                    page.wait_for_timeout(2500)  # 等 SPA 把登录页换成管理界面
                    if page.query_selector("#username") is None:
                        res["stage"] = "logged_in"
                        res["url"] = page.url
                        res["ok"] = True
                        break
                    res["detail"] = "URL 已离开登录页但表单仍在（疑似被顶回）"
                body = ""
                try:
                    body = page.inner_text("body")
                except Exception:
                    pass
                for kw in ("密码错误", "用户名或密码", "认证失败", "登录失败",
                           "账号或密码", "密码不正确", "登录超时"):
                    if kw in body:
                        res["detail"] = f"页面报错: {kw}"
                        res["stage"] = "login_rejected"
                        res["shots"] = _shot(page)
                        return res
            else:
                res["detail"] = "提交后 30 秒仍未进入管理界面"
                res["stage"] = "login_stuck"
                res["shots"] = _shot(page)
                return res

            res["detail"] = "登录成功并跳转"
            res["shots"] = _shot(page)
            return res

        except Exception as exc:  # noqa: BLE001
            res["detail"] = f"{type(exc).__name__}: {str(exc)[:200]}"
            try:
                res["shots"] = _shot(page)
            except Exception:
                pass
            return res
        finally:
            res["elapsed"] = round(time.time() - t0, 1)
            try:
                res["url"] = res["url"] or page.url
            except Exception:
                pass
            browser.close()


def _shot(page) -> dict:
    """留一张现场截图便于事后排查。"""
    try:
        ts = time.strftime("%H%M%S")
        p1 = DUMP / f"fn_login_{ts}.png"
        page.screenshot(path=str(p1), full_page=True)
        return {"screenshot": str(p1)}
    except Exception:
        return {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-login", action="store_true", help="只探测登录页出现，不提交凭证")
    ap.add_argument("--headful", action="store_true")
    args = ap.parse_args()

    r = attempt(headless=not args.headful, do_login=not args.no_login)
    print(json.dumps(r, ensure_ascii=False, indent=2))

    log = DUMP / "fn_login_history.jsonl"
    with log.open("a", encoding="utf-8") as f:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")

    if r["ok"]:
        print(f"\n[✓] NAS 正常（{r['stage']}，耗时 {r['elapsed']}s）")
        return 0
    print(f"\n[✗] 判定异常：{r['stage']} / {r['detail']}（耗时 {r['elapsed']}s）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
