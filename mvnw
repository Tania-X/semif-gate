#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper（自实现，无外部依赖）
#
# 行为：
#   1. 若本地已缓存目标 Maven 发行版则直接使用（兼容官方 wrapper 的 hash 目录布局）
#   2. 否则把发行版下载到 ~/.m2/wrapper/dists 并解压后使用
#
# 覆盖配置（一般不需要）：
#   MAVEN_USER_HOME   缓存根目录，默认 ~/.m2
#   MVNW_CACHE_KEY    缓存子目录名，默认 semif-gate-3.9.9
#   JAVA_HOME         指定 JDK
# ----------------------------------------------------------------------------
set -e

# 解析脚本所在目录（跟随软链接）
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  case "$link" in
    /*) PRG="$link" ;;
    *)  PRG="$(dirname "$PRG")/$link" ;;
  esac
done
BASE_DIR="$(cd "$(dirname "$PRG")" && pwd)"

WRAPPER_PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "错误：找不到 $WRAPPER_PROPS" >&2
  exit 1
fi

# 从 properties 中读取配置（忽略注释与空行，取第一个匹配项）
read_prop() {
  grep "^$1=" "$WRAPPER_PROPS" | head -n 1 | cut -d'=' -f2- | tr -d '\r'
}

DISTRIBUTION_URL="$(read_prop distributionUrl)"
if [ -z "$DISTRIBUTION_URL" ]; then
  echo "错误：maven-wrapper.properties 中缺少 distributionUrl" >&2
  exit 1
fi

# 校验 Java
if [ -z "$JAVA_HOME" ]; then
  JAVACMD="$(command -v java || true)"
  if [ -z "$JAVACMD" ]; then
    echo "错误：未找到 java，请设置 JAVA_HOME 或把 java 加入 PATH" >&2
    exit 1
  fi
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

# 发行版目录名：去掉压缩包扩展名与 "-bin" 后缀
#   例：apache-maven-3.9.9-bin.zip -> apache-maven-3.9.9
DIST_NAME="$(basename "$DISTRIBUTION_URL" | sed 's/\.tar\.gz$//; s/\.zip$//; s/-bin$//')"
MVNW_CACHE_KEY="${MVNW_CACHE_KEY:-semif-gate-3.9.9}"

WRAPPER_ROOT="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists"
DIST_ROOT="$WRAPPER_ROOT/${DIST_NAME}-bin"
INSTALL_DIR="$DIST_ROOT/$MVNW_CACHE_KEY"
MVN_BIN="$INSTALL_DIR/$DIST_NAME/bin/mvn"

# 兼容官方 wrapper 的 hash 目录布局：若已存在同版本发行版，直接复用而不重新下载
if [ ! -x "$MVN_BIN" ] && [ -d "$DIST_ROOT" ]; then
  for candidate in "$DIST_ROOT"/*/"$DIST_NAME"/bin/mvn; do
    if [ -x "$candidate" ]; then
      MVN_BIN="$candidate"
      echo "复用已安装的 Maven 发行版：$(dirname "$(dirname "$candidate")")" >&2
      break
    fi
  done
fi

if [ ! -x "$MVN_BIN" ]; then
  echo "未找到本地 Maven 发行版，正在下载：$DIST_NAME" >&2
  echo "  来源：$DISTRIBUTION_URL" >&2
  mkdir -p "$INSTALL_DIR"
  TMP_ZIP="$INSTALL_DIR/distribution.zip"

  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$TMP_ZIP" "$DISTRIBUTION_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP_ZIP" "$DISTRIBUTION_URL"
  else
    echo "错误：需要 curl 或 wget 来下载 Maven 发行版" >&2
    exit 1
  fi

  ( cd "$INSTALL_DIR" && unzip -q -o distribution.zip && rm -f distribution.zip )

  MVN_BIN="$INSTALL_DIR/$DIST_NAME/bin/mvn"
  if [ ! -x "$MVN_BIN" ]; then
    echo "错误：解压后仍未找到 $MVN_BIN" >&2
    exit 1
  fi
  echo "Maven 已就绪：$INSTALL_DIR/$DIST_NAME" >&2
fi

exec "$MVN_BIN" "$@"
