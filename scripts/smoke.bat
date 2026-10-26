@echo off
rem End-to-end smoke test against a running app: health, register, login, /me, the notes example, refresh,
rem logout, then account deletion, so repeated runs leave no users behind.
rem Usage: scripts\smoke.bat [base-url]   (default http://localhost:8080)
rem Sign-ups create the account only when the emailed link is opened: the link is read from Mailpit
rem (MAILPIT_URL, default http://localhost:8025).
setlocal
set "BASE_URL=%~1"
if "%BASE_URL%"=="" set "BASE_URL=http://localhost:8080"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference = 'Stop'; $base = '%BASE_URL%'; $user = 'smoke_' + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(); $pw = 'Smoke#12345-passphrase';" ^
  "function Check($method, $path, $expected, $body, $token) {" ^
  "  $headers = @{}; if ($token) { $headers['Authorization'] = 'Bearer ' + $token };" ^
  "  $params = @{ Method = $method; Uri = $base + $path; Headers = $headers; UseBasicParsing = $true; ContentType = 'application/json' };" ^
  "  if ($body) { $params['Body'] = $body };" ^
  "  try { $r = Invoke-WebRequest @params; $status = [int]$r.StatusCode; $content = $r.Content }" ^
  "  catch { if (-not $_.Exception.Response) { throw \"$method $path: connection failed (is the app running at $base?)\" }; $status = [int]$_.Exception.Response.StatusCode; $content = '' };" ^
  "  if ($status -ne $expected) { throw \"$method $path: expected $expected, got $status\" };" ^
  "  Write-Host ('==> {0} {1} -> {2}' -f $method, $path, $status); return $content };" ^
  "Check GET '/actuator/health' 200 | Out-Null;" ^
  "Check POST '/api/v1/auth/register' 202 (@{ username = $user; email = \"$user@example.com\"; password = $pw } | ConvertTo-Json) | Out-Null;" ^
  "$mp = if ($env:MAILPIT_URL) { $env:MAILPIT_URL } else { 'http://localhost:8025' }; $id = $null;" ^
  "for ($i = 0; $i -lt 10 -and -not $id; $i++) { $id = (Invoke-RestMethod ($mp + '/api/v1/search?limit=1&query=' + [uri]::EscapeDataString('to:' + $user + '@example.com'))).messages | Select-Object -First 1 -ExpandProperty ID; if (-not $id) { Start-Sleep -Milliseconds 500 } };" ^
  "if (-not $id) { throw \"No verification email for $user@example.com in Mailpit ($mp)\" };" ^
  "$tok = [regex]::Match((Invoke-RestMethod ($mp + '/api/v1/message/' + $id)).Text, 'verify-email\?token=([A-Za-z0-9_-]+)').Groups[1].Value;" ^
  "Check POST '/api/v1/auth/verify-email' 204 (@{ token = $tok } | ConvertTo-Json) | Out-Null;" ^
  "$login = Check POST '/api/v1/auth/login' 200 (@{ identifier = $user; password = $pw } | ConvertTo-Json) | ConvertFrom-Json;" ^
  "Check GET '/api/v1/users/me' 200 $null $login.accessToken | Out-Null;" ^
  "$note = Check POST '/api/v1/notes' 201 (@{ title = 'Smoke test'; body = 'Created by scripts/smoke.bat' } | ConvertTo-Json) $login.accessToken | ConvertFrom-Json;" ^
  "Check GET '/api/v1/notes' 200 $null $login.accessToken | Out-Null;" ^
  "Check PUT ('/api/v1/notes/' + $note.id) 200 (@{ title = 'Smoke test'; body = 'Edited' } | ConvertTo-Json) $login.accessToken | Out-Null;" ^
  "Check DELETE ('/api/v1/notes/' + $note.id) 204 $null $login.accessToken | Out-Null;" ^
  "Check GET ('/api/v1/notes/' + $note.id) 404 $null $login.accessToken | Out-Null;" ^
  "$tokens = Check POST '/api/v1/auth/refresh' 200 (@{ refreshToken = $login.refreshToken } | ConvertTo-Json) | ConvertFrom-Json;" ^
  "Check GET '/api/v1/users' 403 $null $tokens.accessToken | Out-Null;" ^
  "Check GET '/api/v1/users/me' 401 | Out-Null;" ^
  "Check POST '/api/v1/auth/logout' 204 (@{ refreshToken = $tokens.refreshToken } | ConvertTo-Json) $tokens.accessToken | Out-Null;" ^
  "Check GET '/api/v1/users/me' 401 $null $tokens.accessToken | Out-Null;" ^
  "Check POST '/api/v1/auth/refresh' 401 (@{ refreshToken = $tokens.refreshToken } | ConvertTo-Json) | Out-Null;" ^
  "$again = Check POST '/api/v1/auth/login' 200 (@{ identifier = $user; password = $pw } | ConvertTo-Json) | ConvertFrom-Json;" ^
  "Check DELETE '/api/v1/users/me' 204 (@{ password = $pw } | ConvertTo-Json) $again.accessToken | Out-Null;" ^
  "Check POST '/api/v1/auth/login' 401 (@{ identifier = $user; password = $pw } | ConvertTo-Json) | Out-Null;" ^
  "Write-Host ('==> Smoke test passed against ' + $base)"
exit /b %errorlevel%
