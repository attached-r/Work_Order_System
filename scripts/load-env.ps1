# =============================================================================
# Load repository root .env into the current PowerShell session.
#
# Usage (dot-source it; a plain run would only set variables in a child scope):
#   . .\scripts\load-env.ps1
#   cd backend\WorkOrderSystem
#   .\mvnw.cmd spring-boot:run
#
# Handles KEY=VALUE, comments and blank lines. Values are never printed.
# =============================================================================

$envFile = Join-Path $PSScriptRoot '..\.env'

if (-not (Test-Path $envFile)) {
    Write-Host "Missing $envFile" -ForegroundColor Yellow
    Write-Host "Copy the template first: Copy-Item .env.example .env" -ForegroundColor Yellow
    return
}

$count = 0

foreach ($rawLine in Get-Content $envFile) {
    $line = $rawLine.Trim()

    if (-not $line -or $line.StartsWith('#')) {
        continue
    }

    $index = $line.IndexOf('=')
    if ($index -lt 1) {
        continue
    }

    $name = $line.Substring(0, $index).Trim()
    $value = $line.Substring($index + 1).Trim()

    # Remove matching quotes if present.
    if ($value.Length -ge 2) {
        $first = $value.Substring(0, 1)
        $last = $value.Substring($value.Length - 1, 1)
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    Set-Item -Path "Env:$name" -Value $value
    $count++
}

Write-Host "Loaded $count environment variables from .env (current PowerShell session)" -ForegroundColor Green
