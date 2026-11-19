# Starts the REST API and the separate TCP relay for local testing.
param([string] $JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
if ($JavaHome) {
    $java = Join-Path $JavaHome 'bin\java.exe'
} else {
    $java = (Get-Command java.exe).Source
}
$version = & $java --version
if ($LASTEXITCODE -ne 0 -or $version[0] -notmatch '^(?:openjdk|java) (?:21|2[2-9]|[3-9][0-9])\b') {
    throw 'Run this script with Java 21 or later. Specify the JDK folder with -JavaHome if needed.'
}

$relay = $null
$previousSecret = $env:HELPDESK_NOTIFICATIONS_SECRET
$previousJavaHome = $env:JAVA_HOME
Push-Location $PSScriptRoot
try {
    if ($JavaHome) {
        $env:JAVA_HOME = $JavaHome
    }
    $classpathFile = Join-Path $PSScriptRoot 'target\notification-classpath.txt'
    & mvn.cmd -q compile org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not build the help desk server'
    }

    # Both server processes use the same temporary signing key.
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    $env:HELPDESK_NOTIFICATIONS_SECRET = [Convert]::ToBase64String($bytes)
    $classes = Join-Path $PSScriptRoot 'target\classes'
    $classpath = $classes + ';' + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $relayArgs = @('-Dhelpdesk.log.name=notifications', '-cp', ('"' + $classpath + '"'), 'com.ap.helpdesk.server.notification.NotificationServer')
    $relayLog = Join-Path $PSScriptRoot 'target\notification-output.log'
    $relay = Start-Process -FilePath $java -ArgumentList $relayArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput $relayLog -RedirectStandardError (Join-Path $PSScriptRoot 'target\notification-error.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($relay.HasExited) {
            break
        }
        if (Select-String -LiteralPath $relayLog -SimpleMatch 'Notification server listening on local port' -Quiet) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) {
        throw 'The TCP notification server could not start. Check target\notification-error.log.'
    }

    Write-Host 'Starting the REST server and TCP notification server.'
    & $java -cp $classpath com.ap.helpdesk.server.HelpdeskServerApplication
    if ($LASTEXITCODE -ne 0) {
        throw 'The REST server stopped with an error'
    }
} finally {
    if ($relay -and -not $relay.HasExited) {
        Stop-Process -Id $relay.Id -ErrorAction SilentlyContinue
    }
    $env:HELPDESK_NOTIFICATIONS_SECRET = $previousSecret
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
