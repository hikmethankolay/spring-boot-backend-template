@echo off
rem Builds the documentation website into target\pages with Astro Starlight (docs\).
rem See scripts/site.sh for details; CI publishes it on every v* tag. Needs Node.js 22.22+ and the JDK.
rem Usage: scripts\site.bat [--skip-build]   (--skip-build reuses existing test and Javadoc output)
setlocal
cd /d "%~dp0.."

where npm >nul 2>&1 || (
  echo error: Node.js 22.22+ with npm is required to build the documentation site ^(https://nodejs.org^). 1>&2
  exit /b 1
)

if /i not "%~1"=="--skip-build" (
  call "%~dp0lib.bat" require_jdk || exit /b 1
  call mvnw.cmd -B verify || exit /b 1
  call "%~dp0docs.bat" || exit /b 1
)

if not exist "target\openapi.json" (
  echo error: target\openapi.json missing; it is written by the integration tests, which need Docker or Podman. 1>&2
  exit /b 1
)
if not exist "target\reports\apidocs\index.html" (
  echo error: Javadoc output missing; run scripts\docs.bat. 1>&2
  exit /b 1
)
if not exist "target\site\jacoco\jacoco.xml" (
  echo error: JaCoCo report missing; run scripts\test.bat. 1>&2
  exit /b 1
)

rem Copy the generated references into docs\public and compute the build information for the home page.
rem for /f does not pass on PowerShell's exit code: a failure shows as no output.
set "PUBLIC_SITE_COVERAGE="
for /f "usebackq delims=" %%l in (`powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop';" ^
  "foreach ($d in 'docs\public\reference', 'docs\public\coverage') { if (Test-Path $d) { Remove-Item -Recurse -Force $d }; New-Item -ItemType Directory -Force -Path $d | Out-Null };" ^
  "Copy-Item 'target\openapi.json' 'docs\public\api\openapi.json';" ^
  "Copy-Item -Recurse 'target\reports\apidocs\*' 'docs\public\reference';" ^
  "Copy-Item -Recurse 'target\site\jacoco\*' 'docs\public\coverage';" ^
  "$line = ([xml](Get-Content -Raw 'target\site\jacoco\jacoco.xml' -Encoding UTF8)).report.counter | Where-Object type -eq 'LINE';" ^
  "'{0:N1}%%' -f (100 * [int]$line.covered / ([int]$line.covered + [int]$line.missed))"`) do set "PUBLIC_SITE_COVERAGE=%%l"
if not defined PUBLIC_SITE_COVERAGE exit /b 1

for /f "delims=" %%v in ('git describe --tags --abbrev^=0 2^>nul') do set "PUBLIC_SITE_VERSION=%%v"
if not defined PUBLIC_SITE_VERSION set "PUBLIC_SITE_VERSION=dev"
for /f "delims=" %%c in ('git rev-parse --short HEAD') do set "PUBLIC_SITE_COMMIT=%%c"
for /f "delims=" %%d in ('powershell -NoProfile -Command "(Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')"') do set "PUBLIC_SITE_DATE=%%d"

echo ==^> Building the Starlight site
pushd docs
call npm ci --no-audit --no-fund || (popd & exit /b 1)
call npm run build || (popd & exit /b 1)
popd

if exist "target\pages" rmdir /s /q "target\pages"
xcopy /e /i /q "docs\dist" "target\pages" >nul || exit /b 1
type nul > "target\pages\.nojekyll"

echo ==^> Site for %PUBLIC_SITE_VERSION% (%PUBLIC_SITE_COMMIT%, coverage %PUBLIC_SITE_COVERAGE%): target\pages
