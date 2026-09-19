@echo off
chcp 65001 >nul
title 生成执课正式签名密钥库
echo ============================================
echo   执课 正式签名密钥库生成
echo   运行结束后会显示密码和证书指纹
echo   窗口不会自动关闭，请抄录后再按键退出
echo ============================================
echo.
"C:\Users\yutongzhou\.workbuddy\binaries\python\envs\default\Scripts\python.exe" "E:\study\class\cleantable\tools\make_keystore.py"
echo.
echo ============================================
echo   执行完毕。请把上面的 PASSWORD 与指纹抄录/截图保存。
echo ============================================
pause
