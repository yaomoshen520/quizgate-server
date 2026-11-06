@echo off
rem GraalVM native 出包要在 MSVC 环境里跑。Git Bash 里没法直接 cmd //c 'call "C:\路径 带空格\vcvars64.bat"'
rem ——cmd 会把带引号的路径拆开；所以固定走这个 bat。从仓库根目录双击或 cmd //c native-build.bat 都行。
rem 产物是 target\anti-addiction.exe；验收跑 native-smoke.sh。
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
cd /d "%~dp0"
call mvnw.cmd -B -Pnative native:compile
