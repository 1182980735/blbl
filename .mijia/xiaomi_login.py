#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""米家（小米云）登录 & 设备 token 导出。

在本机终端里运行，账号密码只输入给这个进程，不写入任何文件：

    .mijia/venv314/bin/python .mijia/xiaomi_login.py

覆盖小米登录的三种拦截：
  1. 正常登录            -> 直接拿 serviceToken + ssecurity
  2. 两步验证(notificationUrl) -> 打印验证链接，你确认后按回车，同会话重试
  3. 图形验证码(87001)   -> 自动下载验证码图片并弹窗，你照着输入，同会话重提

成功后生成 .mijia/devices.json，内含每台设备的 did / model / 局域网 IP / token。
"""

from __future__ import annotations

import base64
import getpass
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

BASE = Path(__file__).resolve().parent
OUT = BASE / "devices.json"
CAPTCHA = BASE / "captcha.png"
TIMEOUT = 30
LOCALES = ("cn", "de", "i2", "sg", "us", "ru")


def line(title: str) -> None:
    print(f"\n=== {title} ===")


# ---------------------------------------------------------------- 登录


def auth2(session, xiaomi_id: str, password: str, sign: str, captcha: str = "") -> dict:
    """登录第二步：提交账号密码哈希（可按需附带图形验证码）。"""
    url = "https://account.xiaomi.com/pass/serviceLoginAuth2"
    data = {
        "sid": "xiaomiio",
        "hash": hashlib.md5(password.encode()).hexdigest().upper(),
        "callback": "https://sts.api.io.mi.com/sts",
        "qs": "%3Fsid%3Dxiaomiio%26_json%3Dtrue",
        "user": xiaomi_id,
        "_json": "true",
    }
    if sign:
        data["_sign"] = sign
    if captcha:
        data["captchaCode"] = captcha
    r = session.post(url, data=data, timeout=TIMEOUT)
    return json.loads(r.text.replace("&&&START&&&", ""))


def fetch_captcha(session) -> Path | None:
    """从同一个 session 拉图形验证码并存成 png。"""
    url = f"https://account.xiaomi.com/pass/getCode?icodeType=login&{time.time()}"
    r = session.get(url, timeout=TIMEOUT)
    ctype = (r.headers.get("content-type") or "").lower()
    blob = r.content

    # 有时返回 JSON（内嵌 base64），有时直接返回图片二进制，两种都兼容。
    if "json" in ctype or blob[:1] == b"{":
        try:
            js = json.loads(r.text.replace("&&&START&&&", ""))
            b64 = ((js or {}).get("data") or {}).get("captcha") or ""
            if "," in b64:
                b64 = b64.split(",", 1)[1]
            if b64:
                blob = base64.b64decode(b64)
        except Exception:  # noqa: BLE001
            pass

    if len(blob) < 100:
        print("[!] 验证码图片下载异常（接口可能改版），原始响应片段：")
        print("   ", r.text[:200] if r.text else blob[:80])
        return None

    CAPTCHA.write_bytes(blob)
    return CAPTCHA


def ask_captcha() -> str:
    """弹窗展示验证码图片并读取用户输入。"""
    print(f"\n[!] 小米要求图形验证码，图片已存到：{CAPTCHA}")
    try:
        subprocess.run(["open", str(CAPTCHA)], check=False, timeout=10)
        print("[i] 已调用「预览」打开图片，请看窗口里的字母/数字。")
    except Exception:  # noqa: BLE001
        print("[i] 自动打开失败，请手动双击上面这个 png 文件查看。")
    print("[i] 验证码通常 1~2 分钟失效，请尽快输入。")
    try:
        return input("请输入图片中的字符： ").strip()
    except EOFError:
        return ""


def explain_failure(js: dict) -> None:
    code = js.get("code")
    desc = js.get("description") or js.get("desc") or ""
    print(f"\n[x] 登录未通过：code={code} description={desc!r}")
    if code == 70016:
        print("账号或密码不对。注意账号可以是手机号 / 邮箱 / 纯数字小米 ID，别填昵称。")
    elif code in (70008, 70009):
        print("触发了小米风控（登录过于频繁）。等 30 分钟，或先在手机米家 App 正常登录一次。")
    else:
        print("服务端原始响应：")
        print(json.dumps(js, ensure_ascii=False, indent=2)[:1500])


def login(xiaomi_id: str, password: str, max_rounds: int = 6):
    """完整走一遍小米云登录，返回就绪的 MiCloud 对象（含 service_token / ssecurity）。"""
    from micloud import MiCloud

    mc = MiCloud(username=xiaomi_id, password=password)
    mc._init_session()
    try:
        sign = mc._login_step1()
    except Exception as exc:  # noqa: BLE001
        print(f"[x] 连不上 account.xiaomi.com：{exc}\n    检查网络或代理后重试。")
        return None

    captcha = ""
    for rnd in range(1, max_rounds + 1):
        print(f"[i] 第 {rnd} 次提交登录…")
        js = auth2(mc.session, xiaomi_id, password, sign, captcha)
        captcha = ""  # 验证码一次性使用

        if js.get("location"):
            mc.user_id = js.get("userId")
            mc.ssecurity = js.get("ssecurity")
            mc.cuser_id = js.get("cUserId")
            mc.pass_token = js.get("passToken")
            try:
                mc._login_step3(js["location"])
            except Exception as exc:  # noqa: BLE001
                print(f"[!] 兑换 serviceToken 失败：{type(exc).__name__}: {exc}")
            if mc.service_token:
                print("[+] 登录成功：已拿到 serviceToken 与签名密钥 ssecurity")
                return mc
            print("[x] 拿到跳转地址但没换到 serviceToken。")
            return None

        if js.get("notificationUrl"):
            print(
                "\n[!] 小米要求两步验证。请用浏览器打开下面的链接完成验证\n"
                "    （手机米家 App 里点“确认登录”也算），然后回到这里按回车：\n"
            )
            print(f"    {js['notificationUrl']}\n")
            try:
                input("验证完成后按回车继续重试… ")
            except EOFError:
                return None
            continue

        if js.get("captchaUrl") or js.get("code") == 87001:
            sign = js.get("_sign") or sign
            if not fetch_captcha(mc.session):
                return None
            captcha = ask_captcha()
            if not captcha:
                print("[x] 没有输入验证码，退出。（重复失败会抬高风控等级，别硬试）")
                return None
            continue

        explain_failure(js)
        return None

    print("[x] 多次尝试仍未通过。建议等 30 分钟再试，避免风控升级。")
    return None


# ---------------------------------------------------------------- 取设备


def fetch_devices(mc) -> list:
    for country in LOCALES:
        try:
            raw = mc.get_devices(country=country)
        except Exception as exc:  # noqa: BLE001
            print(f"[!] {country} 区查询异常：{type(exc).__name__}: {exc}")
            continue
        if raw:
            print(f"[+] {country} 区拿到 {len(raw)} 台设备")
            return raw
    return []


def slim(raw: dict, locale: str) -> dict:
    return {
        "did": raw.get("did"),
        "token": raw.get("token"),
        "name": raw.get("name"),
        "model": raw.get("model"),
        "ip": raw.get("localip"),
        "mac": raw.get("mac"),
        "ssid": raw.get("ssid"),
        "parent_id": raw.get("parent_id"),
        "isOnline": raw.get("isOnline"),
        "locale": locale,
        "raw": raw,
    }


def main() -> int:
    print(
        "米家设备 token 导出工具\n"
        "----------------------------------------------------------------\n"
        "密码只输入给本进程，不回显、不落盘。\n"
    )

    xiaomi_id = input("小米账号（手机号 / 邮箱 / 小米ID）: ").strip()
    password = getpass.getpass("密码（输入时不显示）: ")
    if not xiaomi_id or not password:
        print("[x] 账号或密码为空，退出。")
        return 2

    line("登录小米云")
    mc = login(xiaomi_id, password)
    if mc is None:
        return 1

    line("拉取设备列表")
    raw_devices = fetch_devices(mc)
    if not raw_devices:
        print("[x] 登录成功但没取到设备，确认这个账号下确实绑定了设备。")
        return 1

    devices = [slim(d, "cn") for d in raw_devices]
    payload = {
        "exported_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "account": xiaomi_id,
        "device_count": len(devices),
        "devices": devices,
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    OUT.chmod(0o600)

    line(f"共 {len(devices)} 台设备")
    plugs = []
    for d in devices:
        kind = "子设备" if d["parent_id"] else "主设备"
        print(f"\n* {d['name'] or '(未命名)'}  [{kind}]")
        print(f"    型号 model : {d['model']}")
        print(f"    局域网 IP  : {d['ip']}   mac: {d['mac']}")
        print(f"    did        : {d['did']}")
        print(f"    token      : {d['token']}")
        hay = f"{d['model'] or ''} {d['name'] or ''}".lower()
        if "plug" in hay or "插座" in hay or "cuco" in hay:
            plugs.append(d)

    line(f"已写入 {OUT}")
    print("该文件含设备 token（等于设备的长期钥匙），权限 600，且已被 .gitignore 排除。")

    if plugs:
        line("识别为插座的设备")
        for d in plugs:
            print(f"  - {d['name']}  model={d['model']}  ip={d['ip']}")
        print("\n下一步：回我一句「好了」，我读 devices.json，做局域网探测与开关/功率控制。")
    else:
        print("\n没识别到 plug/插座 关键字，把上面的清单发我，我来判断哪些能局域网直控。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
