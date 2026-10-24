@echo off
rem Manages the local backing services: PostgreSQL, Redis and Mailpit (dev mail catcher).
rem Usage: scripts\db.bat up^|down^|reset^|logs^|psql^|redis-cli^|status
setlocal
cd /d "%~dp0.."
call "%~dp0lib.bat" detect_compose || exit /b 1

set "SERVICES=db redis mailpit"
set "CMD=%~1"
if "%CMD%"=="" set "CMD=up"

if /i "%CMD%"=="up" (
  echo ==^> Starting PostgreSQL, Redis and Mailpit ^(mail UI: http://localhost:8025^)
  %COMPOSE% up -d --wait %SERVICES%
) else if /i "%CMD%"=="down" (
  %COMPOSE% stop %SERVICES%
) else if /i "%CMD%"=="reset" (
  set /p "ANSWER=This deletes all local database and cache data. Continue? [y/N] "
  call :reset
) else if /i "%CMD%"=="logs" (
  %COMPOSE% logs -f %SERVICES%
) else if /i "%CMD%"=="psql" (
  %COMPOSE% exec db sh -c "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\""
) else if /i "%CMD%"=="redis-cli" (
  %COMPOSE% exec redis redis-cli
) else if /i "%CMD%"=="status" (
  %COMPOSE% ps %SERVICES%
) else (
  echo error: Unknown command '%CMD%'. Use: up^|down^|reset^|logs^|psql^|redis-cli^|status 1>&2
  exit /b 1
)
exit /b %errorlevel%

:reset
if /i not "%ANSWER%"=="y" exit /b 0
%COMPOSE% down -v
%COMPOSE% up -d --wait %SERVICES%
exit /b %errorlevel%
