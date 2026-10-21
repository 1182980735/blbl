#!/bin/bash
# 双击运行：米家设备 token 导出
cd "$(dirname "$0")" || exit 1
PY="./.mijia/venv314/bin/python"
[ -x "$PY" ] || PY="$(command -v python3)"

echo "工作目录: $(pwd)"
"$PY" ./.mijia/xiaomi_login.py
echo
echo "----------------------------------------------------------------"
read -n 1 -s -r -p "按任意键关闭窗口..."
echo
