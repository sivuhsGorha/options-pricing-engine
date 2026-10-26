<#
.SYNOPSIS
  Registers (or removes) a Windows scheduled task that runs scripts\backup.ps1 once a day after the US close.

.DESCRIPTION
  The task runs backup.ps1 with the given -Destination, as the current user, whether or not anyone is logged on,
  and "when available": a laptop that was asleep or shut at the scheduled time runs the backup when it next wakes
  instead of skipping the day. Each run appends its output to <Destination>\scheduled-backup.log, so a failed
  backup (Docker Desktop not running, say) is visible there and in Task Scheduler's history.

  The default time is 16:30 New York (half an hour after the regular close) converted to this machine's clock,
  so it follows US daylight-saving changes only when the task is re-registered. Pass -At to choose another local
  time, for example "22:45".

  Nothing is deleted or overwritten: backup.ps1 writes a new dated folder each run. Registering again with the
  same -Name replaces the previous definition.

.EXAMPLE
  .\scripts\schedule_backup.ps1 -Destination D:\blackthorn-backups
  .\scripts\schedule_backup.ps1 -Destination D:\blackthorn-backups -At 22:45
  .\scripts\schedule_backup.ps1 -Unregister
#>
[CmdletBinding(DefaultParameterSetName = 'Register')]
param(
    [Parameter(ParameterSetName = 'Register', Mandatory = $true)]
    [string]$Destination,
    [Parameter(ParameterSetName = 'Register')]
    [string]$At,
    [Parameter(ParameterSetName = 'Unregister', Mandatory = $true)]
    [switch]$Unregister,
    [string]$Name = 'Blackthorn Sterling backup'
)

$ErrorActionPreference = 'Stop'

if ($Unregister) {
    $existing = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if ($null -eq $existing) { Write-Host "No scheduled task named '$Name'."; return }
    Unregister-ScheduledTask -TaskName $Name -Confirm:$false
    Write-Host "Removed the scheduled task '$Name'."
    return
}

$backup = Join-Path $PSScriptRoot 'backup.ps1'
if (-not (Test-Path $backup)) { throw "backup.ps1 is not beside this script ($backup)" }
if ($Destination -notmatch '^[A-Za-z]:\\' -and $Destination -notmatch '^\\\\') { throw "-Destination must be an absolute path (a drive or a UNC share), got '$Destination'" }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

if ([string]::IsNullOrWhiteSpace($At)) {
    # 16:30 in New York today, expressed on this machine's clock.
    $newYork = [System.TimeZoneInfo]::FindSystemTimeZoneById('Eastern Standard Time')
    $todayNy = [System.TimeZoneInfo]::ConvertTime([DateTime]::UtcNow, $newYork).Date.AddHours(16).AddMinutes(30)
    $local = [System.TimeZoneInfo]::ConvertTime([DateTime]::SpecifyKind($todayNy, 'Unspecified'), $newYork, [System.TimeZoneInfo]::Local)
    $At = $local.ToString('HH:mm')
}
$parsed = [DateTime]::MinValue
if (-not [DateTime]::TryParseExact($At, 'HH:mm', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
    throw "-At must be a local time as HH:mm, got '$At'"
}

$log = Join-Path $Destination 'scheduled-backup.log'
# Single quotes inside the -Command string survive Task Scheduler; the paths were checked above and hold no quote.
$command = "& { & '$backup' -Destination '$Destination' *>> '$log' }"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command `"$command`""
$trigger = New-ScheduledTaskTrigger -Daily -At $parsed
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType S4U -RunLevel Limited

Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null

Write-Host "Registered '$Name': every day at $At local time, backup.ps1 -Destination $Destination"
Write-Host "Output goes to $log. Check it after the first run, and run the task once by hand to see it work:"
Write-Host "  Start-ScheduledTask -TaskName '$Name'"
