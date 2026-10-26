@echo off
rem Makes the release commit and tag from main, then pushes both after asking (see scripts/release.sh).
rem Runs scripts/release.sh with Git Bash, which ships with Git for Windows.
rem Usage: scripts\release.bat <version>      e.g. scripts\release.bat 4.1.0
setlocal
cd /d "%~dp0.."

set "BASH="
for %%p in ("%ProgramFiles%\Git\bin\bash.exe" "%ProgramFiles(x86)%\Git\bin\bash.exe" "%LocalAppData%\Programs\Git\bin\bash.exe") do (
  if not defined BASH if exist "%%~p" set "BASH=%%~p"
)
if not defined BASH (
  echo error: Git Bash not found. Install Git for Windows ^(winget install Git.Git^) or run scripts/release.sh in WSL. 1>&2
  exit /b 1
)

"%BASH%" scripts/release.sh %*
