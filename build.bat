@echo off
rem ===========================================================================
rem  McQqChatPool - build script (Maven variant - see the "maven" branch)
rem
rem  Usage:
rem    build.bat               build, then deploy the jar if a target dir is found
rem    build.bat nopause       same, but do not wait for a key press at the end
rem
rem  Optional environment variables:
rem    JAVA_HOME          JDK 25 home (checked; ignored if it is not JDK 25)
rem    MCQQ_DEPLOY_DIR    where to copy the built jar, e.g. ...\server\plugins
rem
rem  Requirements: JDK 25. Maven itself is not required - the bundled wrapper
rem  (mvnw.cmd) downloads it on first run.
rem
rem  NOTE: keep this file ASCII-only. cmd.exe parses .bat files with the OEM
rem  codepage, so non-ASCII text corrupts the script.
rem ===========================================================================
setlocal
set "PLUGIN_DIR=%~dp0"
if "%PLUGIN_DIR:~-1%"=="\" set "PLUGIN_DIR=%PLUGIN_DIR:~0,-1%"
set "JAR=%PLUGIN_DIR%\target\mc-qq-chatpool-1.0.0.jar"

set "NOPAUSE="
if /i "%~1"=="nopause" set "NOPAUSE=1"

rem ------------------------------------------------- locate a JDK 25 ---
echo [1/3] Locating JDK 25 ...
set "JDK="
call :try_jdk "%JAVA_HOME%"
if not defined JDK call :try_jdk "%PLUGIN_DIR%\..\tools\jdk25.0.4_10"
if not defined JDK for %%D in ("%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Amazon Corretto\jdk-25*" "%ProgramFiles%\Microsoft\jdk-25*" "%ProgramFiles%\Zulu\zulu-25*" "%ProgramFiles%\BellSoft\LibericaJDK-25*") do if not defined JDK call :try_jdk "%%~fD"
if not defined JDK (
  echo.
  echo [ERROR] No JDK 25 found. Paper 26.x requires Java 25.
  echo         Install JDK 25 ^(any vendor^) and either set JAVA_HOME to it,
  echo         or drop this folder next to a "tools\jdk25.0.4_10" directory.
  goto :fail
)
set "JAVA_HOME=%JDK%"

rem -------------------------------------------------- Maven command ---
set "MVN_CMD="
if exist "%PLUGIN_DIR%\mvnw.cmd" set "MVN_CMD=%PLUGIN_DIR%\mvnw.cmd"
if not defined MVN_CMD set "MVN_CMD=mvn"

rem --------------------------------------------------- deploy target ---
set "DEPLOY_DIR="
if defined MCQQ_DEPLOY_DIR set "DEPLOY_DIR=%MCQQ_DEPLOY_DIR%"
if not defined DEPLOY_DIR if exist "%PLUGIN_DIR%\..\server\plugins" set "DEPLOY_DIR=%PLUGIN_DIR%\..\server\plugins"

echo.
echo [2/3] Building McQqChatPool with Maven ...
echo        JAVA_HOME = %JAVA_HOME%
echo        Maven     = %MVN_CMD%
call "%MVN_CMD%" -B -f "%PLUGIN_DIR%\pom.xml" package
if errorlevel 1 (
  echo.
  echo [ERROR] Build failed.
  goto :fail
)
if not exist "%JAR%" (
  echo [ERROR] Build finished but the jar is missing: %JAR%
  goto :fail
)

echo.
if defined DEPLOY_DIR (
  echo [3/3] Copying the jar into %DEPLOY_DIR% ...
  if not exist "%DEPLOY_DIR%" mkdir "%DEPLOY_DIR%"
  copy /y "%JAR%" "%DEPLOY_DIR%\" >nul
  if errorlevel 1 (
    echo [ERROR] Copy failed. Is the server running and holding the file? Stop it and retry.
    goto :fail
  )
  echo        Jars now in %DEPLOY_DIR%:
  dir /b "%DEPLOY_DIR%\*.jar"
) else (
  echo [3/3] No deploy target found - skipping the copy.
  echo        Set MCQQ_DEPLOY_DIR to your server's plugins folder, e.g.:
  echo          set MCQQ_DEPLOY_DIR=D:\my-server\plugins
  echo        Built jar: %JAR%
)

echo.
echo Next: start the server once, then edit plugins\McQqChatPool\config.yml
echo       ^(set http.token first^) and run /chatpool reload or restart the server.
goto :done

rem --------------------------------------------------------------------------
rem  :try_jdk <path>  - accept the path only when it is a JDK 25
rem --------------------------------------------------------------------------
:try_jdk
if "%~1"=="" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
"%~1\bin\java.exe" -version 2>&1 | findstr /r /c:"version .25" >nul
if errorlevel 1 (
  echo        skipped %~1 ^(not JDK 25^)
  exit /b 0
)
set "JDK=%~1"
echo        using %~1
exit /b 0

:fail
if not defined NOPAUSE pause
exit /b 1

:done
if not defined NOPAUSE pause
exit /b 0
