@echo off
rem Runs the k6 load test (load-tests\api.js) against a running app, using the k6 container image.
rem All requests come from one IP, so start the target app with RATE_LIMIT_ENABLED=false
rem (and REQUIRE_VERIFIED_EMAIL=false). With FAILED_LOGINS=true, also set LOGIN_MAX_ATTEMPTS=1000000
rem and LOGIN_LOCK_DURATION=1m, or the run locks the IP out.
rem Usage: scripts\load-test.bat [base-url]   (default http://host.docker.internal:8080)
rem Env:   PROFILE (smoke, load, stress, spike, soak or throughput; default load), VUS (peak users, default 20),
rem        DURATION (time at the peak), THINK_TIME (default 1), USERS (default 20), FAILED_LOGINS.
rem        ADMIN_USERNAME and ADMIN_PASSWORD (read from .env when not set) enable the admin journey.
rem See load-tests\api.js for the journeys and profiles.
setlocal
cd /d "%~dp0.."

set "CONTAINER="
docker info >nul 2>&1 && set "CONTAINER=docker"
if not defined CONTAINER podman info >nul 2>&1 && set "CONTAINER=podman"
if not defined CONTAINER (
  echo error: Docker or Podman is required ^(and must be running^). 1>&2
  exit /b 1
)

set "BASE_URL=%~1"
if "%BASE_URL%"=="" set "BASE_URL=http://host.docker.internal:8080"
if exist ".env" (
  if not defined ADMIN_USERNAME for /f "tokens=1,* delims==" %%a in ('findstr /b "ADMIN_USERNAME=" .env') do set "ADMIN_USERNAME=%%b"
  if not defined ADMIN_PASSWORD for /f "tokens=1,* delims==" %%a in ('findstr /b "ADMIN_PASSWORD=" .env') do set "ADMIN_PASSWORD=%%b"
)

if not defined PROFILE (set "SHOWN_PROFILE=load") else set "SHOWN_PROFILE=%PROFILE%"
if not defined VUS (set "SHOWN_VUS=20") else set "SHOWN_VUS=%VUS%"
echo ==^> Load testing %BASE_URL% (profile %SHOWN_PROFILE%, %SHOWN_VUS% peak users)
rem "-e NAME" passes the variable only when it is set, so the defaults stay in api.js.
%CONTAINER% run --rm -v "%CD%\load-tests:/scripts:ro" -e BASE_URL -e MAILPIT_URL -e PROFILE -e VUS -e DURATION -e THINK_TIME -e USERS -e FAILED_LOGINS -e ADMIN_USERNAME -e ADMIN_PASSWORD docker.io/grafana/k6:1.4.0 run /scripts/api.js
