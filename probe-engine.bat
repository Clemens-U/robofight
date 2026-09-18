@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\Users\Clemens\Documents\github\robofight
set "KSTD=C:\Users\Clemens\tools\kotlinc\lib\kotlin-stdlib.jar"
if exist build\probe-cls rmdir /s /q build\probe-cls
call C:\Users\Clemens\tools\kotlinc\bin\kotlinc.bat ^
  engine/src/robofight/isa/ISA.kt ^
  engine/src/robofight/assembler/Assembler.kt ^
  engine/src/robofight/vm/Vm.kt ^
  engine/src/robofight/world/World.kt ^
  engine/src/robofight/world/TextGrid.kt ^
  engine/src/robofight/world/Presets.kt ^
  engine/src/robofight/world/Simulator.kt ^
  engine/src/robofight/Tests.kt ^
  -d build/probe-cls
echo "KOTLINC_EXIT=%ERRORLEVEL%"
if not "%ERRORLEVEL%"=="0" goto end
java -cp build/probe-cls;%KSTD% robofight.TestsKt
echo "RUN_EXIT=%ERRORLEVEL%"
:end
endlocal
