@REM Copyright 2022-2026 DATA @ UHN. See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.
@REM
@REM Licensed under the Apache License, Version 2.0 (the "License");
@REM you may not use this file except in compliance with the License.
@REM You may obtain a copy of the License at
@REM
@REM     http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing, software
@REM distributed under the License is distributed on an "AS IS" BASIS,
@REM WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@REM See the License for the specific language governing permissions and
@REM limitations under the License.

@REM All of the actual start logic lives in start.py, shared between this
@REM wrapper (Windows) and start.sh (Linux, macOS, WSL).
@REM Keep platform-specific logic in start.py, not in the wrappers.

@echo off
setlocal

cd /d "%~dp0"

where /q py
if errorlevel 1 goto TryPython
py -3 start.py %*
exit /b %errorlevel%

:TryPython
where /q python
if errorlevel 1 goto NoPython
python start.py %*
exit /b %errorlevel%

:NoPython
echo Python 3 is required to start IAP, but neither 'py' nor 'python' was found on the PATH. 1>&2
exit /b 1
