@echo off
setlocal
set PATH=C:\Users\Ran\.workbuddy\binaries\node\versions\22.22.2;%PATH%
cd /d "D:\Program Files (x86)\WorkBuddy\siem\socp\frontend\apps\workbench"
if exist dist rmdir /s /q dist
call "C:\Users\Ran\.workbuddy\binaries\node\versions\22.22.2\corepack.cmd" pnpm@10.0.0 run build
