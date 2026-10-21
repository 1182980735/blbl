#!/bin/bash
# 把会话 session-779143a0-bc9d-4953-8612-32aa0227a0d6
# 从工作区 blbl-main 迁移到默认工作区 default-workspace。
#
# 用法：完全退出 DeepSeek Harness（⌘Q）后，在「终端」里运行：
#     bash "/Users/mac/Documents/trae_projects/blbl-main/.mijia/migrate_session.sh"
#
# 做四件事：校验环境 -> 备份 -> 移动会话数据目录 -> 改写两个寄存器 json
# 任何一步失败都会中止，已有数据保持原样。

set -euo pipefail

SID="session-779143a0-bc9d-4953-8612-32aa0227a0d6"
SRC_ENC="--Users-mac-Documents-trae_projects-blbl-main--"
DST_ENC="--Users-mac-Documents-deepseek-harness-default-workspace--"
SRC_CWD="/Users/mac/Documents/trae_projects/blbl-main"
DST_CWD="/Users/mac/Documents/deepseek-harness/default-workspace"
WS_BLBL="77cd1795-642a-4308-b2db-ba5d17003b5e"
WS_DEFAULT="a430ce03-de94-44fb-85d5-f23a7f191e3c"

DSH="$HOME/.dsh"
SESS_SRC="$DSH/sessions/$SRC_ENC/$SID"
SESS_DST="$DSH/sessions/$DST_ENC/$SID"
WS_JSON="$DSH/storages/workspace.json"
PC_JSON="$DSH/storages/session_projcache/sessions/$SID.json"
BK="$DSH/backup-migrate-$(date +%Y%m%d-%H%M%S)"

echo "== 会话迁移：blbl-main -> default-workspace =="

# 0) 必须确认 DSH 已退出，否则会写坏正在写入的会话数据
if pgrep -f "DeepSeek Harness.app" >/dev/null 2>&1; then
  echo "✗ DeepSeek Harness 仍在运行。请先完全退出（⌘Q），再重新运行本脚本。"
  exit 1
fi
echo "✓ DSH 未在运行"

# 1) 前置校验
[ -d "$SESS_SRC" ] || { echo "✗ 找不到会话数据目录: $SESS_SRC"; exit 1; }
[ -d "$DSH/sessions/$DST_ENC" ] || { echo "✗ 找不到默认工作区会话目录: $DSH/sessions/$DST_ENC"; exit 1; }
[ -f "$WS_JSON" ] || { echo "✗ 找不到 $WS_JSON"; exit 1; }
[ -f "$PC_JSON" ] || { echo "✗ 找不到 $PC_JSON"; exit 1; }
if [ -e "$SESS_DST" ]; then
  echo "✗ 目标已存在同名会话目录，已中止: $SESS_DST"
  exit 1
fi
[ -d "$DST_CWD" ] || { echo "✗ 默认工作区目录不存在: $DST_CWD"; exit 1; }
echo "✓ 前置校验通过"

# 2) 备份
mkdir -p "$BK"
cp "$WS_JSON" "$BK/workspace.json"
cp "$PC_JSON" "$BK/$(basename "$PC_JSON")"
cp -R "$SESS_SRC" "$BK/$SID.session-backup"
echo "✓ 已备份到 $BK"

# 3) 移动会话数据目录
mv "$SESS_SRC" "$SESS_DST"
echo "✓ 会话数据已移动 -> $SESS_DST"

# 4) 改写 workspace.json 与 projcache
python3 - "$WS_JSON" "$PC_JSON" "$WS_BLBL" "$WS_DEFAULT" "$SID" "$DST_CWD" <<'PY'
import json, sys, pathlib

ws_path, pc_path, ws_blbl, ws_def, sid, dst_cwd = sys.argv[1:7]

ws = json.loads(pathlib.Path(ws_path).read_text(encoding="utf-8"))
works = ws["tables"]["workspaces"]
if sid not in works[ws_blbl]["sessionIds"]:
    print(f"! 注意：{sid} 原本不在 blbl-main 的 sessionIds 里，仍继续")
for wid in (ws_blbl, ws_def):
    works[wid]["sessionIds"] = [s for s in works[wid]["sessionIds"] if s != sid]
works[ws_def]["sessionIds"].append(sid)
pathlib.Path(ws_path).write_text(json.dumps(ws, ensure_ascii=False, indent=2), encoding="utf-8")
print("  workspace.json 已更新")

pc = json.loads(pathlib.Path(pc_path).read_text(encoding="utf-8"))
old = pc["record"]["identity"]["cwd"]
pc["record"]["identity"]["cwd"] = dst_cwd
pathlib.Path(pc_path).write_text(json.dumps(pc, ensure_ascii=False), encoding="utf-8")
print(f"  projcache cwd: {old} -> {dst_cwd}")
PY

echo "✓ 寄存器已改写"
echo
echo "完成。现在重新打开 DeepSeek Harness，该会话应出现在 default-workspace 下。"
echo "若不满意，可用备份回滚：$BK"
