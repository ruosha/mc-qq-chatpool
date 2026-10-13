# ============================================================================
#  McQqChatPool - end to end test
#
#  What it does:
#    0. builds the plugin (unless -SkipBuild) and copies the jar into <ServerDir>\plugins
#    1. temporarily enables RCON in server.properties (restored at the end)
#    2. starts the Paper server and waits until it is really up
#    3. runs the /chatpool commands over RCON and checks the output
#    4. exercises every HTTP endpoint and asserts the status codes
#    5. stops the server gracefully and verifies that state.yml was written
#
#  Requirements: Paper 26.2 server directory + JDK 25 (same one the server uses).
#
#  Example:
#    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chatpool-e2e-test.ps1 `
#      -ServerDir D:\mc-server -JavaExe D:\jdk-25\bin\java.exe
#
#  Windows PowerShell 5.1 and PowerShell 7 both work.
# ============================================================================
[CmdletBinding()]
param(
    # Paper server directory (contains the server jar, server.properties, logs\, plugins\).
    [Parameter(Mandatory = $true)][string]$ServerDir,
    # Plugin source directory (defaults to the repository root).
    # NOTE: $PSScriptRoot is not reliably populated inside param() defaults on
    # Windows PowerShell 5.1, so the default is resolved in the body below.
    [string]$PluginDir = '',
    # java.exe to launch the server with. Defaults to whatever is on PATH.
    [string]$JavaExe = 'java',
    # Server jar. Defaults to the single paper-*.jar found in ServerDir.
    [string]$ServerJar = '',
    # Plugin jar. Defaults to <PluginDir>\target\mc-qq-chatpool-1.0.0.jar
    # (the Maven output dir on this branch; on the main/Gradle branch it is build\libs\)
    [string]$JarPath = '',
    # Build script to invoke (unless -SkipBuild). Defaults to <PluginDir>\build.bat,
    # the portable one. Point it somewhere else when your toolchain lives outside
    # the repository, e.g. -BuildScript .\build-local.bat
    [string]$BuildScript = '',
    [int]$RconPort = 25575,
    [string]$RconPassword = 'mcqq-e2e-test',
    [string]$HttpBase = 'http://127.0.0.1:8123',
    # Token for the HTTP API. Defaults to the value found in the plugin's config.yml.
    [string]$HttpToken = '',
    [int]$StartupTimeoutSeconds = 300,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'

# --------------------------------------------------------------------- setup --
if (-not $PluginDir) { $PluginDir = Split-Path -Parent $PSScriptRoot }
$ServerDir = (Resolve-Path $ServerDir).Path
$PluginDir = (Resolve-Path $PluginDir).Path
if (-not $JarPath) { $JarPath = Join-Path $PluginDir 'target\mc-qq-chatpool-1.0.0.jar' }
if (-not $BuildScript) { $BuildScript = Join-Path $PluginDir 'build.bat' }
if (-not $ServerJar) {
    $candidate = Get-ChildItem $ServerDir -Filter 'paper-*.jar' -File | Select-Object -First 1
    if (-not $candidate) { throw "No paper-*.jar found in $ServerDir - pass -ServerJar explicitly." }
    $ServerJar = $candidate.Name
}

$pluginDataDir = Join-Path $ServerDir 'plugins\McQqChatPool'
$logFile       = Join-Path $ServerDir 'logs\latest.log'
$propsFile     = Join-Path $ServerDir 'server.properties'
$propsBackup   = "$propsFile.e2e-bak"
$outLog        = Join-Path $ServerDir 'console-e2e.log'
$errLog        = Join-Path $ServerDir 'console-e2e-err.log'
$pluginConfig  = Join-Path $pluginDataDir 'config.yml'
$maxLength     = 300   # chat.max-message-length; the real value is read from the plugin config below

$script:Passed = 0
$script:Failed = 0

function Step($message) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $message) }
function Assert($name, $condition, $detail = '') {
    if ($condition) {
        $script:Passed++
        Write-Host ("  [PASS] {0}" -f $name)
    } else {
        $script:Failed++
        Write-Host ("  [FAIL] {0} {1}" -f $name, $detail)
    }
}

function Wait-TcpPort([string]$target, [int]$port, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $client = New-Object Net.Sockets.TcpClient
            $client.Connect($target, $port)
            if ($client.Connected) { $client.Close(); return $true }
        } catch { Start-Sleep -Milliseconds 400 }
    }
    return $false
}

function Test-PortOpen([string]$target, [int]$port) {
    try {
        $client = New-Object Net.Sockets.TcpClient
        $client.Connect($target, $port)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Send-Rcon($stream, [int]$id, [int]$type, [string]$body) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $ms = New-Object IO.MemoryStream
    $bw = New-Object IO.BinaryWriter($ms)
    $bw.Write([int](4 + 4 + $bytes.Length + 2)); $bw.Write([int]$id); $bw.Write([int]$type)
    $bw.Write($bytes); $bw.Write([byte]0); $bw.Write([byte]0); $bw.Flush()
    $packet = $ms.ToArray()
    $stream.Write($packet, 0, $packet.Length); $stream.Flush()
}

function Read-Rcon($stream) {
    $lenBytes = New-Object byte[] 4; $read = 0
    while ($read -lt 4) { $n = $stream.Read($lenBytes, $read, 4 - $read); if ($n -le 0) { return $null }; $read += $n }
    $length = [BitConverter]::ToInt32($lenBytes, 0)
    if ($length -lt 10 -or $length -gt 65536) { return $null }
    $buffer = New-Object byte[] $length; $read = 0
    while ($read -lt $length) { $n = $stream.Read($buffer, $read, $length - $read); if ($n -le 0) { return $null }; $read += $n }
    $bodyLength = $length - 10
    $body = if ($bodyLength -gt 0) { [Text.Encoding]::UTF8.GetString($buffer, 8, $bodyLength) } else { '' }
    [pscustomobject]@{ Id = [BitConverter]::ToInt32($buffer, 0); Body = $body }
}

function Clean-Console($text) {
    $pattern = ([string][char]0xA7) + '.'
    ($text -replace $pattern, '') -replace '\s+$', ''
}

function Convert-Json($text) {
    if (-not $text) { return $null }
    try { return $text | ConvertFrom-Json } catch { return $null }
}

function Api($method, $path, $bodyObject = $null, [hashtable]$headers = @{}) {
    $params = @{ Uri = "$HttpBase$path"; Method = $method; TimeoutSec = 15; UseBasicParsing = $true }
    if ($headers.Count -gt 0) { $params.Headers = $headers }
    if ($null -ne $bodyObject) {
        $json = if ($bodyObject -is [string]) { $bodyObject } else { $bodyObject | ConvertTo-Json -Compress }
        $params.Body = [Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $response = Invoke-WebRequest @params
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $response.Content }
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -ne $webResponse) {
            $reader = New-Object IO.StreamReader($webResponse.GetResponseStream(), [Text.Encoding]::UTF8)
            $text = $reader.ReadToEnd(); $reader.Close()
            return [pscustomobject]@{ Status = [int]$webResponse.StatusCode; Body = $text }
        }
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}

# ------------------------------------------------------------------- build ----
Step 'Building the plugin'
if (-not $SkipBuild) {
    if (-not (Test-Path $BuildScript)) { throw "Build script not found: $BuildScript" }
    & cmd.exe /c "`"$BuildScript`" nopause" 2>&1 | Select-String -Pattern 'BUILD|ERROR|error:' | ForEach-Object { "    " + $_.Line }
}
if (-not (Test-Path $JarPath)) { throw "Plugin jar not found: $JarPath (build it first, or drop -SkipBuild)" }
if (-not (Test-Path (Join-Path $ServerDir 'plugins'))) { New-Item -ItemType Directory -Path (Join-Path $ServerDir 'plugins') | Out-Null }
Copy-Item $JarPath (Join-Path $ServerDir 'plugins') -Force
Step "Deployed $(Split-Path -Leaf $JarPath) into plugins"

if (-not $HttpToken -and (Test-Path $pluginConfig)) {
    $match = Select-String -Path $pluginConfig -Pattern '^\s*token:\s*"?(.*?)"?\s*$' | Select-Object -First 1
    if ($match) { $HttpToken = $match.Matches[0].Groups[1].Value }
}
if (Test-Path $pluginConfig) {
    $limitMatch = Select-String -Path $pluginConfig -Pattern '^\s*max-message-length:\s*(\d+)' | Select-Object -First 1
    if ($limitMatch) { $maxLength = [int]$limitMatch.Matches[0].Groups[1].Value }
}
$auth = @{}
if ($HttpToken) { $auth = @{ Authorization = "Bearer $HttpToken" } }

# ---------------------------------------------------------------- preflight --
# The test starts its own server, so nothing may already hold the ports we need
# (otherwise a running instance answers our probes and the results are bogus).
$serverPort = 25565
if (Test-Path $propsFile) {
    $portMatch = Select-String -Path $propsFile -Pattern '^\s*server-port\s*=\s*(\d+)' | Select-Object -First 1
    if ($portMatch) { $serverPort = [int]$portMatch.Matches[0].Groups[1].Value }
}
$httpPort = ([Uri]$HttpBase).Port
$busy = @()
if (Test-PortOpen '127.0.0.1' $serverPort) { $busy += "game port $serverPort" }
if (Test-PortOpen '127.0.0.1' $httpPort)   { $busy += "plugin http port $httpPort" }
if (Test-PortOpen '127.0.0.1' $RconPort)   { $busy += "rcon port $RconPort" }
if ($busy.Count -gt 0) {
    throw ("These ports are already in use: {0}. Stop the running server (or pass other ports) first." -f ($busy -join ', '))
}
if (-not (Test-Path $propsFile)) { throw "server.properties not found in $ServerDir" }

# ------------------------------------------------------- enable RCON (temp) ---
Step 'Enabling RCON temporarily (server.properties is restored afterwards)'
Copy-Item $propsFile $propsBackup -Force
$lines = [ordered]@{}
foreach ($line in [IO.File]::ReadAllLines($propsFile)) {
    if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
    $kv = $line.Split('=', 2)
    $lines[$kv[0]] = $kv[1]
}
$lines['enable-rcon']           = 'true'
$lines['rcon.port']             = "$RconPort"
$lines['rcon.password']         = $RconPassword
$lines['broadcast-rcon-to-ops'] = 'false'
[IO.File]::WriteAllLines($propsFile, @($lines.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }))

$serverProcess = $null
$client = $null
$stream = $null
try {
    # ------------------------------------------------------------- start -----
    Step 'Starting the server'
    Remove-Item $outLog, $errLog -ErrorAction SilentlyContinue
    if (Test-Path $logFile) {
        Remove-Item $logFile -Force -ErrorAction SilentlyContinue
        if (Test-Path $logFile) { throw "Cannot remove $logFile - it is locked by another process. Is a server still running?" }
    }
    Remove-Item (Join-Path $pluginDataDir 'state.yml') -ErrorAction SilentlyContinue
    $serverProcess = Start-Process -FilePath $JavaExe `
        -ArgumentList @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', "-Djava.io.tmpdir=./.tmp", '-Xms2G', '-Xmx2G', '-jar', $ServerJar, '--nogui') `
        -WorkingDirectory $ServerDir -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Step "Server PID=$($serverProcess.Id), waiting for it to come up"

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $done = $false
    while ((Get-Date) -lt $deadline) {
        if ($serverProcess.HasExited) { break }
        if (Test-Path $logFile) {
            $content = Get-Content $logFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($content -match 'Done \(') { $done = $true; break }
        }
        Start-Sleep -Milliseconds 1000
    }
    $rconReady = $done -and (Wait-TcpPort '127.0.0.1' $RconPort 60)
    $httpReady = Wait-TcpPort '127.0.0.1' ([Uri]$HttpBase).Port 60
    Step "world loaded=$done rcon=$rconReady plugin http=$httpReady"
    Assert 'server world loaded' $done
    Assert 'rcon port listening' $rconReady
    Assert 'plugin http port listening' $httpReady
    if (-not ($done -and $rconReady)) { throw 'Server did not become ready - see logs\latest.log' }
    Start-Sleep -Seconds 2

    Step 'Plugin startup log'
    Select-String -Path $logFile -Pattern 'McQqChatPool' -ErrorAction SilentlyContinue |
        ForEach-Object { "    " + $_.Line }

    # -------------------------------------------------------------- rcon -----
    Step 'Running /chatpool commands over RCON'
    $client = New-Object Net.Sockets.TcpClient('127.0.0.1', $RconPort)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 20000
    Send-Rcon $stream 1 3 $RconPassword
    $login = Read-Rcon $stream
    Assert 'rcon login' ($null -ne $login -and $login.Id -ne -1)

    $rconOutput = @{}
    foreach ($command in @('plugins', 'chatpool help', 'chatpool list',
                           'chatpool send global e2e from console',
                           'chatpool info global', 'chatpool history global 5',
                           'chatpool who global', 'chatpool reload')) {
        Send-Rcon $stream 2 2 $command
        $response = Read-Rcon $stream
        $text = if ($response) { Clean-Console $response.Body } else { '' }
        $rconOutput[$command] = $text
        Write-Host "    /$command -> $($text -replace "`r?`n", ' | ')"
        Start-Sleep -Milliseconds 250
    }
    Assert '/plugins lists McQqChatPool' ($rconOutput['plugins'] -match 'McQqChatPool')
    Assert '/chatpool help prints usage' ($rconOutput['chatpool help'] -match '/chatpool')
    Assert '/chatpool list lists pools' ($rconOutput['chatpool list'] -match 'global')
    Assert '/chatpool send accepted' (($rconOutput['chatpool send global e2e from console'] -notmatch 'Unknown') -and $rconOutput['chatpool send global e2e from console'].Length -gt 0)
    Assert '/chatpool info works' ($rconOutput['chatpool info global'] -match 'global')
    Assert '/chatpool reload works' ($rconOutput['chatpool reload'].Length -gt 0)

    # -------------------------------------------------------------- http -----
    Step 'Checking the HTTP API'
    if (-not $httpReady) {
        Write-Host '    plugin HTTP port is not listening - check the startup log above'
    }
    $response = Api 'GET' '/api/v1/health' $null $auth
    Assert 'GET /health with token -> 200' ($response.Status -eq 200) "got $($response.Status)"
    Assert 'GET /health without token -> 401' ((Api 'GET' '/api/v1/health').Status -eq 401)
    Assert 'GET /health with wrong token -> 401' ((Api 'GET' '/api/v1/health' $null @{ Authorization = 'Bearer wrong' }).Status -eq 401)
    if ($HttpToken) {
        Assert 'GET /health with ?token= -> 200' ((Api 'GET' "/api/v1/health?token=$HttpToken").Status -eq 200)
    }

    $response = Api 'GET' '/api/v1/pools' $null $auth
    $pools = Convert-Json $response.Body
    Assert 'GET /pools -> 200' ($response.Status -eq 200)
    Assert 'GET /pools returns the default pool' (@($pools.pools.name) -contains 'global')

    $tail = Convert-Json (Api 'GET' '/api/v1/messages?pool=global' $null $auth).Body
    Assert 'GET /messages tail mode works' ($tail -and $tail.ok -and $tail.mode -eq 'tail')
    Assert 'GET /messages sees the console message' (@($tail.messages.source) -contains 'console')

    $incremental = Convert-Json (Api 'GET' '/api/v1/messages?pool=global&since=0&exclude_source=qq' $null $auth).Body
    Assert 'GET /messages incremental (exclude qq) has no qq entries' (-not (@($incremental.messages.source) -contains 'qq'))

    $injected = Convert-Json (Api 'POST' '/api/v1/messages' @{ pool = 'global'; sender = 'e2e'; sender_id = '1'; content = 'injected by e2e test' } $auth).Body
    Assert 'POST /messages injects a message' ($injected -and $injected.ok -and [int]$injected.id -gt 0)

    $afterInject = Convert-Json (Api 'GET' '/api/v1/messages?pool=global&since=0&exclude_source=qq' $null $auth).Body
    Assert 'injected qq message is filtered by exclude_source' (@($afterInject.messages.source) -notcontains 'qq')
    $afterInjectAll = Convert-Json (Api 'GET' '/api/v1/messages?pool=global&since=0' $null $auth).Body
    Assert 'injected qq message is visible without the filter' (@($afterInjectAll.messages.source) -contains 'qq')

    Assert 'GET /players -> 200' ((Api 'GET' '/api/v1/players?pool=global' $null $auth).Status -eq 200)
    Assert 'unknown pool -> 404' ((Api 'GET' '/api/v1/messages?pool=nosuchpool' $null $auth).Status -eq 404)
    Assert 'illegal pool name -> 400' ((Api 'GET' '/api/v1/messages?pool=Bad-Name%21' $null $auth).Status -eq 400)
    Assert 'empty content -> 400' ((Api 'POST' '/api/v1/messages' @{ pool = 'global'; content = '   ' } $auth).Status -eq 400)
    $tooLong = 'x' * ($maxLength + 1)
    Assert "content longer than $maxLength -> 413" ((Api 'POST' '/api/v1/messages' @{ pool = 'global'; content = $tooLong } $auth).Status -eq 413)
    Assert 'broken json -> 400' ((Api 'POST' '/api/v1/messages' '{ not json' $auth).Status -eq 400)
    Assert 'method not allowed -> 405' ((Api 'DELETE' '/api/v1/messages' $null $auth).Status -eq 405)
    Assert 'unknown path -> 404' ((Api 'GET' '/api/v1/nope' $null $auth).Status -eq 404)
    Assert 'out of range limit is clamped -> 200' ((Api 'GET' '/api/v1/messages?pool=global&since=0&limit=99999' $null $auth).Status -eq 200)
} finally {
    # Stop the server gracefully even when a check above threw.
    if ($stream) {
        Step 'Stopping the server'
        try { Send-Rcon $stream 2 2 'stop'; $null = Read-Rcon $stream } catch { }
        if ($client) { try { $client.Close() } catch { } }
        $client = $null; $stream = $null
    }
    if ($serverProcess) {
        if (-not $serverProcess.WaitForExit(120000)) {
            Step 'Server did not stop in time - killing it'
            Stop-Process -Id $serverProcess.Id -Force
        } else {
            Step 'Server stopped'
        }
    }
    if (Test-Path $propsBackup) { Move-Item $propsBackup $propsFile -Force }
    Step 'server.properties restored (RCON off)'
}

# ---------------------------------------------------------------- results ----
Step 'Checking persisted state'
$stateFile = Join-Path $pluginDataDir 'state.yml'
if (Test-Path $stateFile) {
    Write-Host "    state.yml:"
    Get-Content $stateFile -Encoding UTF8 | Select-Object -First 20 | ForEach-Object { "      $_" }
}
Assert 'state.yml written on shutdown' (Test-Path $stateFile)
Assert 'plugin config.yml generated' (Test-Path $pluginConfig)

Write-Host ''
Write-Host ("Results:{0} passed, {1} failed" -f $script:Passed, $script:Failed)
if ($script:Failed -gt 0) { exit 1 }
exit 0
