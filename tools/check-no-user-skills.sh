#!/usr/bin/env bash
# 发布包零 skill 门禁（S0.8）：仓库 / engine.zip / APK 都不得包含用户 skill。
#
#   tools/check-no-user-skills.sh repo
#   tools/check-no-user-skills.sh engine <engine.zip>
#   tools/check-no-user-skills.sh apk <apk>
#   tools/check-no-user-skills.sh dirty
#
# 规则见 docs 发布纪律：仓库只允许 dsh 自身 npm 包里的 builtin 内容
# （engine 模式白名单 node_modules/@deepseek-ai/）；用户 skill 只能安装后在 App 内导入。
# 失败 exit 1，成功打印 OK。
set -euo pipefail

mode="${1:?usage: $0 repo|engine <engine.zip>|apk <apk>|dirty}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

fail() { printf 'FAIL(%s): %s\n' "$mode" "$*" >&2; exit 1; }
# 列出 zip 条目：优先 unzip（CI 自带），没有时退回 python3（本机可能没装 unzip）。
zip_list() {
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$1"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys, zipfile; [print(n) for n in zipfile.ZipFile(sys.argv[1]).namelist()]' "$1"
  else
    fail "need unzip or python3 to inspect $1"
  fi
}

# 用户 skill 的形态：SKILL.md、skills/ 目录、*.skill 包、本地 dsh/venv 目录。
PATTERN='(^|/)SKILL\.md$|(^|/)skills/|\.skill$|^\.dsh/|^\.venv/'

case "$mode" in
  repo)
    # 只看被 git 跟踪的文件；LICENSE/第三方版权文本不匹配这些模式。
    hits="$(git ls-files | grep -Ei "$PATTERN" || true)"
    [ -z "$hits" ] || fail "tracked skill/user files:
$hits"
    # 未跟踪或被忽略的本地文件：提醒，不直接失败（不提交即可）。
    ignored="$(git status --porcelain --ignored 2>/dev/null | grep -Ei 'skills|\.dsh|\.venv|\.skill' | grep -Ev 'tools/check-no-user-skills\.sh' || true)"
    [ -z "$ignored" ] || printf 'WARN(repo): ignored/untracked user files present (do not commit):\n%s\n' "$ignored" >&2
    ;;
  engine)
    engine_zip="${2:?engine <engine.zip>}"
    [ -f "$engine_zip" ] || fail "no such engine zip: $engine_zip"

    hits="$(zip_list "$engine_zip" | grep -Ei '(^|/)SKILL\.md$|(^|/)skills/|\.skill$' || true)"
    # dsh 自身的 builtin 内容（@deepseek-ai/ 下的包）允许保留。
    hits="$(printf '%s\n' "$hits" | grep -Ev '^node_modules/@deepseek-ai/' || true)"
    [ -z "$hits" ] || fail "user skills inside engine.zip:
$hits"
    ;;
  apk)
    apk="${2:?apk <apk>}"
    [ -f "$apk" ] || fail "no such apk: $apk"

    # 只查非 engine 资产：engine.zip 由 engine 模式单独扫描。
    hits="$(zip_list "$apk" | grep -Ei '(^|/)SKILL\.md$|assets/skills|(^|/)skills/|\.skill$' \
      | grep -Ev 'assets/engine\.zip$' || true)"
    [ -z "$hits" ] || fail "skill content in APK (outside engine.zip):
$hits"
    ;;
  dirty)
    hits="$(git status --porcelain | grep -Ei 'SKILL\.md|skills/|\.dsh/|\.venv/|\.skill$' || true)"
    [ -z "$hits" ] || fail "dirty skill files (do not commit or release):
$hits"
    ;;
  *)
    fail "unknown mode (repo|engine|apk|dirty)"
    ;;
esac

echo "OK($mode)"
