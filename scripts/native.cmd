@echo off
setlocal
for /f "usebackq delims=" %%V in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "GLSL_VS=%%V"
if not defined GLSL_VS exit /b 1
call "%GLSL_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
set "PATH=%GLSL_VS%\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja;%PATH%"
if not defined GLSL_BUILD_JOBS set "GLSL_BUILD_JOBS=8"
if defined GLSL_PYTHON set "GLSL_PYTHON_EXE=%GLSL_PYTHON%"
if not defined GLSL_PYTHON_EXE for /d %%P in ("%USERPROFILE%\AppData\Local\Python\pythoncore-*-64") do if exist "%%P\python.exe" set "GLSL_PYTHON_EXE=%%P\python.exe"
if not defined GLSL_PYTHON_EXE for /f "delims=" %%P in ('python -c "import sys;print(sys.executable)"') do set "GLSL_PYTHON_EXE=%%P"
if not defined GLSL_PYTHON_EXE exit /b 1
pushd "%~dp0.."
cmake -S native -B build/compiler -G Ninja -DCMAKE_BUILD_TYPE=Release "-DPython3_EXECUTABLE=%GLSL_PYTHON_EXE%" || exit /b 1
cmake --build build/compiler --target compiler_tests --parallel %GLSL_BUILD_JOBS% || exit /b 1
ctest --test-dir build/compiler --output-on-failure || exit /b 1
popd
