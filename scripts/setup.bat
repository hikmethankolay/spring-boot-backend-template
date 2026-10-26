@echo off
rem One-time setup: checks prerequisites, creates .env with generated secrets and compiles the project.
rem Without a JDK but with Docker or Podman, it skips compiling: scripts\docker.bat up builds and runs the app in containers.
rem Usage: scripts\setup.bat [--force]   (--force recreates .env)
setlocal
cd /d "%~dp0.."

echo ==^> Checking prerequisites
set "JDK=true"
call "%~dp0lib.bat" detect_compose >nul 2>&1
if errorlevel 1 (
  call "%~dp0lib.bat" require_jdk || exit /b 1
  echo warning: Docker or Podman not found: db.bat, docker.bat and integration tests need one. 1>&2
) else (
  echo ==^> Container runtime found
  call "%~dp0lib.bat" require_jdk >nul 2>&1 || set "JDK=false"
)
if "%JDK%"=="false" echo warning: No JDK 25+ found: the app can still run in containers ^(scripts\docker.bat up^); dev, test and build need a JDK. 1>&2

if exist ".env" if /i not "%~1"=="--force" (
  echo ==^> .env already exists ^(use --force to recreate it^)
  goto compile
)

echo ==^> Creating .env from .env.example with generated secrets
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "function Secret([int]$n) { $b = New-Object byte[] $n; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) -replace '[+/=]','' };" ^
  "$admin = Secret 18;" ^
  "(Get-Content .env.example) -replace '^JWT_SECRET=.*', ('JWT_SECRET=' + (Secret 48)) -replace '^MFA_ENCRYPTION_KEY=.*', ('MFA_ENCRYPTION_KEY=' + (Secret 48)) -replace '^ADMIN_PASSWORD=.*', ('ADMIN_PASSWORD=' + $admin) | Set-Content -Encoding ascii .env;" ^
  "Write-Host ('==> Initial admin password: ' + $admin + ' (stored in .env; change it after the first login)')"
if errorlevel 1 exit /b 1

:compile
if "%JDK%"=="false" (
  echo ==^> Done. Next: scripts\docker.bat up to build and start everything in containers, then scripts\smoke.bat.
  exit /b 0
)
echo ==^> Compiling (downloads dependencies on first run)
call mvnw.cmd -B -q -DskipTests compile || exit /b 1

echo ==^> Done. Next: scripts\dev.bat to start the database and the app.
