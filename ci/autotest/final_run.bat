@echo off
rem HowRead 自动测试一键全量（真机层）：L0 冒烟 + L1 功能回归
cd /d Z:\opt\librera\LibreraReader\ci\autotest
echo ===== DEVICE L0 =====
python run_all.py --level L0
echo ===== DEVICE L1 =====
python run_all.py --level L1
echo ===== ALL DONE（结果见 results\ 下最新目录）=====
