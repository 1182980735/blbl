#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 Playwright 驱动本机 Chrome 观察飞牛远程访问门户，抓取登录相关请求。

    .mijia/venv314/bin/python .mijia/fn_probe.py            # 只观察
    .mijia/venv314/bin/python .mijia/fn_probe.py --login    # 观察并尝试登录
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

BASE = pathlib.Path(__file__).resolve().parent
OUT = BASE / "dump"
ENTRY = "https://anhuihl.5ddd.com/"
ACCOUNT = "admin"
PASSWORD = "hl123456"

INTERESTING = ("api", "login", "auth", "sign", "token", "user", "con")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--login", action="store_true")
    ap.add_argument("--headful", action="store_true")
    args = ap.parse_args()

    from playwright.sync_api import sync_playwright

    OUT.mkdir(parents=True, exist_ok=True)
    events: list[dict] = []

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=not args.headful)
        ctx = browser.new_context(ignore_https_errors=True, locale="zh-CN")
        page = ctx.new_page()

        def on_request(r):
            events.append({
                "kind": "req", "method": r.method, "url": r.url,
                "post": (r.post_data or "")[:800],
                "headers": {k: v for k, v in r.headers.items()},
            })

        def on_response(r):
            body = ""
            if any(k in r.url.lower() for k in INTERESTING):
                try:
                    body = r.text()[:600]
                except Exception:
                    body = "<binary>"
            events.append({"kind": "resp", "status": r.status, "url": r.url, "body": body})

        page.on("request", on_request)
        page.on("response", on_response)

        print(f"[*] 打开 {ENTRY}")
        page.goto(ENTRY, wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(6000)
        print(f"[*] 落地 URL: {page.url}")
        print(f"[*] 标题: {page.title()!r}")
        body_text = page.inner_text("body")[:1200]
        print(f"[*] 可见文本:\n{body_text}\n")

        page.screenshot(path=str(OUT / "fn_step1.png"), full_page=True)

        if args.login:
            print("[*] 尝试填写登录表单…")
            filled = False
            for sel in ("input[type=password]", "input[type='password']"):
                if page.query_selector(sel):
                    page.fill(sel, PASSWORD)
                    filled = True
                    break
            for sel in ("input[name*=user]", "input[type=text]", "input[placeholder*='账']",
                        "input[placeholder*='用户']", "input[placeholder*='邮箱']"):
                el = page.query_selector(sel)
                if el:
                    el.fill(ACCOUNT)
                    break
            if filled:
                for sel in ("button[type=submit]", "button:has-text('登录')", "text=登录",
                            "button:has-text('登陆')"):
                    el = page.query_selector(sel)
                    if el:
                        el.click()
                        print(f"[*] 已点击 {sel}")
                        break
                else:
                    page.keyboard.press("Enter")
                    print("[*] 回车提交")
                page.wait_for_timeout(8000)
                print(f"[*] 提交后 URL: {page.url}")
                print(f"[*] 提交后文本:\n{page.inner_text('body')[:1200]}")
                page.screenshot(path=str(OUT / "fn_step2.png"), full_page=True)
            else:
                print("[!] 没找到密码输入框，页面结构需要人工确认（看截图）")

        browser.close()

    log = OUT / "fn_network.json"
    log.write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[*] 共 {len(events)} 条网络事件，已写入 {log}")
    print("\n=== 关键请求 ===")
    for e in events:
        if e["kind"] != "req":
            continue
        u = e["url"].lower()
        if e["method"] != "GET" or any(k in u for k in INTERESTING):
            hdr = {k: v for k, v in e["headers"].items()
                   if k.lower() in ("fn-sign", "content-type", "authorization", "cookie", "referer", "origin")}
            print(f"  {e['method']:4} {e['url'][:110]}")
            if e["post"]:
                print(f"        body: {e['post'][:200]}")
            if hdr:
                print(f"        hdr : {json.dumps(hdr, ensure_ascii=False)[:260]}")
    print("\n=== 关键响应 ===")
    for e in events:
        if e["kind"] == "resp" and any(k in e["url"].lower() for k in INTERESTING):
            print(f"  {e['status']} {e['url'][:110]}")
            if e["body"]:
                print(f"        {e['body'][:300]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
