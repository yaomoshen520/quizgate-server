@echo off
rem GraalVM native build has to run inside the MSVC environment, and Git Bash cannot call
rem vcvars64.bat itself (cmd splits the quoted path). Output: target\anti-addiction.exe.
rem Acceptance: bash native-smoke.sh. Build the web bundle first (cd web && npm run build);
rem its output lands in src\main\resources\static and the image freezes what is in there
rem at compile time.
rem
rem ASCII only, on purpose. cmd parses this file in the OEM codepage, and a UTF-8 Chinese
rem comment can swallow the CR right after it: two lines become one, and the tail of the
rem comment runs as a command. Measured on 2026-11-08 - that is how the rmdir below got
rem eaten and the image shipped the previous build's bundles. Keep prose in
rem native-smoke.sh, which is UTF-8 safe.
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cd /d "%~dp0"
rem Maven's resources plugin copies into target\classes incrementally and never deletes, so
rem target\classes\static only ever grows: every past build's hashed files sit there, the old
rem entry page stays reachable forever, and a warm browser cache runs it without saying so.
rem Delete that one layer only - not all of target - because native-smoke.sh reuses the protoc
rem that lives under target\protobuf-maven-plugin. The trailing backslash is required: cmd's
rem "if exist" answers false for a directory without it.
if exist "target\classes\static\" rmdir /s /q "target\classes\static"
if exist "target\classes\static\" (
  echo BUILD ABORTED: target\classes\static survived deletion, image would ship old bundles
  exit /b 1
)
call mvnw.cmd -B -Pnative native:compile
