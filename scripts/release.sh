#!/usr/bin/env bash
# SimplyWork 一键发版脚本
#
# 流程：前置检查 → 单元测试 → 编译 release APK → 验签 → 打 tag → 创建 GitHub Release 并上传 APK
#
# 用法：
#   scripts/release.sh            # 正式发版（版本号取自 app/build.gradle.kts 的 versionName）
#   scripts/release.sh --dry-run  # 只做前置检查并打印发版计划，不测试/编译/打 tag/发 release
#
# 前置条件：
#   - 处于与 origin/main 一致的干净 main 分支
#   - local.properties 已配置 RELEASE_* 签名四项（否则编译产物是 unsigned，脚本会拒绝）
#   - gh CLI 已登录（gh auth login）
#   - CHANGELOG.md 中存在 ## [versionName] 章节（用作 release notes）
set -euo pipefail

cd "$(dirname "$0")/.."

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
elif [[ -n "${1:-}" ]]; then
  echo "未知参数：$1（支持 --dry-run）" >&2
  exit 2
fi

log()  { printf '\n==> %s\n' "$*"; }
warn() { printf '警告：%s\n' "$*" >&2; }
die()  { printf '错误：%s\n' "$*" >&2; exit 1; }

# ---- 1. 读取版本 ----------------------------------------------------------
VERSION=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -1 || true)
[[ -n "$VERSION" ]] || die "无法从 app/build.gradle.kts 读取 versionName"
TAG="v$VERSION"
DIST_APK="app/build/outputs/apk/release/SimplyWork-$TAG.apk"
log "版本 ${VERSION}（tag ${TAG}）"

# ---- 2. 前置检查 ----------------------------------------------------------
[[ "$(git branch --show-current)" == "main" ]] || die "请先切到 main 分支发版（当前：$(git branch --show-current)）"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  die "工作区有未提交的改动，请先提交或 stash"
fi
if [[ -n "$(git status --porcelain)" ]]; then
  warn "存在未跟踪文件（不参与 git，但会被本地构建包含）："
  git status --porcelain | sed 's/^/    /' >&2
fi

log "同步 origin/main"
git fetch origin main --tags --quiet || die "无法访问 origin，检查网络"
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)
[[ "$LOCAL" == "$REMOTE" ]] || die "本地 main 与 origin/main 不一致（local=${LOCAL} remote=${REMOTE}），先 git pull / push 对齐"

command -v gh >/dev/null 2>&1 || die "需要 GitHub CLI（gh），见 https://cli.github.com/"
gh auth status >/dev/null 2>&1 || die "gh 未登录，请先运行：gh auth login"

if ! grep -q '^RELEASE_STORE_FILE=' local.properties 2>/dev/null; then
  die "local.properties 缺少 RELEASE_STORE_FILE 等签名配置（见 README「从源码构建」）"
fi

# ---- 3. release notes（取自 CHANGELOG 对应章节）---------------------------
VER_RE=$(printf '%s' "$VERSION" | sed 's/\./\\./g')
NOTES_FILE=$(mktemp)
trap 'rm -f "$NOTES_FILE"' EXIT
awk -v re="$VER_RE" '
  $0 ~ "^## \\[" re "\\]" {found=1; next}
  /^## \[/               {if (found) exit}
  found                  {print}
' CHANGELOG.md > "$NOTES_FILE"
[[ -s "$NOTES_FILE" ]] || die "CHANGELOG.md 中没有 ## [$VERSION] 章节，发版前请补写"
log "release notes（前 5 行）"
head -5 "$NOTES_FILE" | sed 's/^/    /'

# ---- 4. 发版计划 ----------------------------------------------------------
cat <<EOF

发版计划：
    1. ./gradlew :app:testDebugUnitTest
    2. ./gradlew :app:assembleRelease
    3. apksigner verify $DIST_APK
    4. git tag -a $TAG && git push origin $TAG
    5. gh release create $TAG 上传 SimplyWork-$TAG.apk
EOF

# ---- 5. tag 占用检查 ------------------------------------------------------
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  die "tag $TAG 已存在（本地），如需重发先删除旧 tag/release"
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  die "tag $TAG 已存在于 origin"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  log "--dry-run：前置检查全部通过，未执行任何发版动作"
  exit 0
fi

# ---- 6. 测试 + 编译 -------------------------------------------------------
log "运行单元测试"
./gradlew :app:testDebugUnitTest

log "编译 release APK"
./gradlew :app:assembleRelease

SRC_APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$SRC_APK" ]]; then
  if [[ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]]; then
    die "产物是 unsigned（签名配置未生效，检查 local.properties 四项 RELEASE_*）"
  fi
  die "未找到编译产物 $SRC_APK"
fi

# ---- 7. 验签 --------------------------------------------------------------
find_sdk() {
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$sdk" && -f local.properties ]]; then
    sdk=$(sed -nE 's/^sdk\.dir=(.*)$/\1/p' local.properties | head -1)
  fi
  if [[ -z "$sdk" ]]; then
    case "$(uname -s)" in
      Darwin) sdk="$HOME/Library/Android/sdk" ;;
      Linux)  sdk="$HOME/Android/Sdk" ;;
    esac
  fi
  printf '%s' "$sdk"
}
SDK=$(find_sdk)
APKSIGNER=$(ls "${SDK:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | tail -1 || true)
[[ -n "$APKSIGNER" ]] || die "找不到 apksigner（SDK=${SDK}），请设置 ANDROID_HOME 或 local.properties 的 sdk.dir"
log "验证 APK 签名"
"$APKSIGNER" verify --print-certs "$SRC_APK" >/dev/null || die "apksigner 验签失败，产物不可发布"

cp -f "$SRC_APK" "$DIST_APK"
log "产物：${DIST_APK}（$(du -h "$DIST_APK" | cut -f1)）"

# ---- 8. 打 tag + 发布 -----------------------------------------------------
log "创建并推送 tag $TAG"
git tag -a "$TAG" -m "Release $TAG"
if ! git push origin "$TAG"; then
  git tag -d "$TAG" >/dev/null
  die "推送 tag 失败（已回滚本地 tag），重试：scripts/release.sh"
fi

log "创建 GitHub Release 并上传 APK"
if ! gh release create "$TAG" "$DIST_APK" --title "$TAG" --notes-file "$NOTES_FILE"; then
  die "release 创建失败。tag $TAG 已在远端，可手动补发：gh release create $TAG '$DIST_APK' --title $TAG --notes-file '$NOTES_FILE'"
fi

log "完成"
gh release view "$TAG" --json url --jq .url
