@REM ---------------------------------------------------------------------------
@REM Apache Maven Wrapper（only-script 模式）—— Windows 版
@REM ---------------------------------------------------------------------------
@echo off
setlocal

set "BASE_DIR=%~dp0"
set "WRAPPER_PROPS=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties"

if not exist "%WRAPPER_PROPS%" (
  echo 错误：找不到 %WRAPPER_PROPS% 1>&2
  exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPS%") do (
  if "%%A"=="distributionUrl" set "DISTRIBUTION_URL=%%B"
)

if "%DISTRIBUTION_URL%"=="" (
  echo 错误：maven-wrapper.properties 中缺少 distributionUrl 1>&2
  exit /b 1
)

if "%JAVA_HOME%"=="" (
  set "JAVACMD=java"
) else (
  set "JAVACMD=%JAVA_HOME%\bin\java"
)

for %%F in ("%DISTRIBUTION_URL%") do set "DIST_FILE=%%~nxF"
for %%F in ("%DIST_FILE%") do set "DIST_NAME=%%~nF"

if "%MAVEN_USER_HOME%"=="" set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "MAVEN_HOME_DIR=%MAVEN_USER_HOME%\wrapper\dists\%DIST_NAME%"
set "MVN_BIN=%MAVEN_HOME_DIR%\%DIST_NAME%\bin\mvn.cmd"

if not exist "%MVN_BIN%" (
  echo 未找到本地 Maven 发行版，正在下载：%DIST_NAME% 1>&2
  mkdir "%MAVEN_HOME_DIR%" 2>nul
  powershell -NoProfile -Command "Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '%MAVEN_HOME_DIR%\distribution.zip'" || exit /b 1
  powershell -NoProfile -Command "Expand-Archive -Force '%MAVEN_HOME_DIR%\distribution.zip' '%MAVEN_HOME_DIR%'" || exit /b 1
  del "%MAVEN_HOME_DIR%\distribution.zip"
)

call "%MVN_BIN%" %*
endlocal
