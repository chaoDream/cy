#!/usr/bin/env python3
"""
从 backend/application-local.yml 同步密钥到 deploy/.env（仅覆盖映射字段，保留数据库/JWT/Nginx 等部署专有项）。

用法:
  ./scripts/sync-env-from-local.py              # 更新本地 deploy/.env
  ./scripts/sync-env-from-local.py --remote     # 同时更新服务器 deploy/.env（需 deploy.local.env）
  ./scripts/sync-env-from-local.py --dry-run    # 只打印将写入的键值

建议在修改 application-local.yml 后执行；配合 push-deploy.sh --sync-env 可在发版时自动同步到服务器。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

DEPLOY_ROOT = Path(__file__).resolve().parent.parent
REPO_ROOT = DEPLOY_ROOT.parent
LOCAL_YAML = REPO_ROOT / "backend" / "application-local.yml"
LOCAL_ENV = DEPLOY_ROOT / ".env"
REMOTE_ENV_FILE = DEPLOY_ROOT / "deploy.local.env"

# 只从 YAML 同步这些键；deploy/.env 里其余项（POSTGRES_*、JWT_SECRET 等）保持不变
MANAGED_KEYS = [
    "AFFILIATE_MOCK",
    "JD_PROVIDER_PRIMARY",
    "JD_PROVIDER_FALLBACK",
    "PDD_PROVIDER_PRIMARY",
    "PDD_PROVIDER_FALLBACK",
    "VEAPI_BASE_URL",
    "VEAPI_VEKEY",
    "VEAPI_SECRET",
    "VEAPI_JD_UNION_ID",
    "VEAPI_JD_POSITION_ID",
    "VEAPI_JD_SCENE_ID",
    "VEAPI_JD_CHAIN_TYPE",
    "JD_APP_KEY",
    "JD_APP_SECRET",
    "JD_UNION_ID",
    "PDD_CLIENT_ID",
    "PDD_CLIENT_SECRET",
    "PDD_PID",
    "WX_APPID",
    "WX_SECRET",
    "AI_MOCK",
    "AI_BASE_URL",
    "AI_API_KEY",
    "AI_MODEL_HIGH",
    "AI_MODEL_FAST",
]


def _scalar(text: str, key: str) -> str:
    m = re.search(rf"(?m)^\s*{re.escape(key)}:\s*(.+)$", text)
    if not m:
        return ""
    return m.group(1).strip().strip('"').strip("'")


def _block(text: str, header: str) -> str:
    """提取 header 下同级缩进子块（遇同级或更浅缩进键则结束）。"""
    m = re.search(rf"(?m)^{re.escape(header)}:\s*\n", text)
    if not m:
        return ""
    header_indent = len(header) - len(header.lstrip(" "))
    start = m.end()
    lines: list[str] = []
    for line in text[start:].splitlines():
        if not line.strip():
            lines.append(line)
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent <= header_indent and line.strip():
            break
        lines.append(line)
    return "\n".join(lines)


def _fallback_list(jd_or_pdd_block: str) -> str:
    items = re.findall(r"(?m)^\s+-\s+(\S+)\s*$", jd_or_pdd_block)
    return ",".join(items) if items else ""


def load_yaml_mapping(yaml_path: Path) -> dict[str, str]:
    if not yaml_path.is_file():
        sys.exit(f"未找到 {yaml_path}，请先复制 application-local.yml.example 并填写密钥。")

    text = yaml_path.read_text(encoding="utf-8")
    affiliate = _block(text, "  affiliate")
    provider = _block(affiliate, "    provider")
    veapi = _block(affiliate, "    veapi")
    veapi_jd = _block(veapi, "      jd")
    aff_jd = _block(affiliate, "    jd")
    aff_pdd = _block(affiliate, "    pdd")
    jd_route = _block(provider, "      jd")
    pdd_route = _block(provider, "      pdd")
    wechat = _block(text, "  wechat")
    ai = _block(text, "  ai")

    return {
        "AFFILIATE_MOCK": _scalar(affiliate, "mock"),
        "JD_PROVIDER_PRIMARY": _scalar(jd_route, "primary"),
        "JD_PROVIDER_FALLBACK": _fallback_list(jd_route) or _scalar(jd_route, "fallback"),
        "PDD_PROVIDER_PRIMARY": _scalar(pdd_route, "primary"),
        "PDD_PROVIDER_FALLBACK": _fallback_list(pdd_route) or _scalar(pdd_route, "fallback"),
        "VEAPI_BASE_URL": _scalar(veapi, "base-url") or "http://api.veapi.cn",
        "VEAPI_VEKEY": _scalar(veapi, "vekey"),
        "VEAPI_SECRET": _scalar(veapi, "secret"),
        "VEAPI_JD_UNION_ID": _scalar(veapi_jd, "union-id"),
        "VEAPI_JD_POSITION_ID": _scalar(veapi_jd, "position-id"),
        "VEAPI_JD_SCENE_ID": _scalar(veapi_jd, "scene-id") or "2",
        "VEAPI_JD_CHAIN_TYPE": _scalar(veapi_jd, "chain-type") or "1",
        "JD_APP_KEY": _scalar(aff_jd, "app-key"),
        "JD_APP_SECRET": _scalar(aff_jd, "app-secret"),
        "JD_UNION_ID": _scalar(aff_jd, "union-id"),
        "PDD_CLIENT_ID": _scalar(aff_pdd, "client-id"),
        "PDD_CLIENT_SECRET": _scalar(aff_pdd, "client-secret"),
        "PDD_PID": _scalar(aff_pdd, "pid"),
        "WX_APPID": _scalar(wechat, "appid"),
        "WX_SECRET": _scalar(wechat, "secret"),
        "AI_MOCK": _scalar(ai, "mock") or "false",
        "AI_BASE_URL": _scalar(ai, "base-url") or "https://api.deepseek.com/v1",
        "AI_API_KEY": _scalar(ai, "api-key"),
        "AI_MODEL_HIGH": _scalar(ai, "model-high") or "deepseek-v4-flash",
        "AI_MODEL_FAST": _scalar(ai, "model-fast") or "deepseek-v4-flash",
    }


def merge_env_file(env_path: Path, updates: dict[str, str], dry_run: bool) -> None:
    lines: list[str] = []
    if env_path.is_file():
        lines = env_path.read_text(encoding="utf-8").splitlines()
    else:
        example = DEPLOY_ROOT / ".env.example"
        if example.is_file():
            lines = example.read_text(encoding="utf-8").splitlines()
        else:
            lines = []

    index_by_key: dict[str, int] = {}
    for i, line in enumerate(lines):
        if line.startswith("#") or "=" not in line:
            continue
        key = line.split("=", 1)[0]
        index_by_key[key] = i

    for key in MANAGED_KEYS:
        val = updates.get(key, "")
        new_line = f"{key}={val}"
        if key in index_by_key:
            lines[index_by_key[key]] = new_line
        else:
            lines.append(new_line)

    if dry_run:
        print(f"# {env_path}")
        for key in MANAGED_KEYS:
            print(f"{key}={updates.get(key, '')}")
        return

    env_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    print(f"已更新 {env_path}（{len(MANAGED_KEYS)} 项来自 application-local.yml）")


def sync_remote(updates: dict[str, str], dry_run: bool) -> None:
    if not REMOTE_ENV_FILE.is_file():
        sys.exit(f"未找到 {REMOTE_ENV_FILE}，无法同步到服务器。")

    cfg: dict[str, str] = {}
    for line in REMOTE_ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        cfg[k] = v

    host = cfg.get("DEPLOY_HOST", "")
    user = cfg.get("DEPLOY_USER", "")
    path = cfg.get("DEPLOY_PATH", "")
    if not host or not user or not path:
        sys.exit("deploy.local.env 缺少 DEPLOY_HOST / DEPLOY_USER / DEPLOY_PATH")

    remote_env = f"{path}/deploy/.env"
    script_lines = [
        "#!/bin/bash",
        "set -euo pipefail",
        f'ENV_FILE="{remote_env}"',
        "touch \"$ENV_FILE\"",
    ]
    for key in MANAGED_KEYS:
        val = updates.get(key, "").replace("\\", "\\\\").replace('"', '\\"')
        script_lines.append(
            f'grep -q "^{key}=" "$ENV_FILE" && '
            f'sed -i "s|^{key}=.*|{key}={val.replace("|", "\\|")}|" "$ENV_FILE" || '
            f'echo "{key}={val}" >> "$ENV_FILE"'
        )
    script_lines.append('echo "remote ok"')

    remote_script = "\n".join(script_lines) + "\n"
    if dry_run:
        print(f"# remote {user}@{host}:{remote_env}")
        for key in MANAGED_KEYS:
            print(f"{key}={updates.get(key, '')}")
        return

    ssh_key = cfg.get("DEPLOY_SSH_KEY", "")
    ssh_cmd = ["ssh", "-o", "BatchMode=yes"]
    if ssh_key:
        ssh_key = ssh_key.replace("~", str(Path.home()))
        ssh_cmd.extend(["-i", ssh_key])
    ssh_cmd.extend([f"{user}@{host}", "bash -s"])

    proc = subprocess.run(ssh_cmd, input=remote_script.encode(), capture_output=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr.decode())
        sys.exit(proc.returncode)
    print(f"已更新服务器 {user}@{host}:{remote_env}")


def main() -> None:
    parser = argparse.ArgumentParser(description="从 application-local.yml 同步密钥到 deploy/.env")
    parser.add_argument("--remote", action="store_true", help="同时更新服务器 deploy/.env")
    parser.add_argument("--dry-run", action="store_true", help="只打印，不写入")
    args = parser.parse_args()

    updates = load_yaml_mapping(LOCAL_YAML)
    merge_env_file(LOCAL_ENV, updates, args.dry_run)
    if args.remote:
        sync_remote(updates, args.dry_run)


if __name__ == "__main__":
    main()
