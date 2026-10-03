# =============================================================================
# windows-firewall.ps1 - Mở cổng MQTT cho thiết bị trong LAN (chạy trên Windows)
#
# CÁCH CHẠY: mở PowerShell bằng quyền Administrator rồi:
#   powershell -ExecutionPolicy Bypass -File .\scripts\windows-firewall.ps1
#
# Script chỉ THÊM rule (không xóa rule nào). Cổng lấy từ .env (LOCAL_PORT=8883,
# SERVER_PORT=8884). Chỉ mở cho profile "Private" (mạng nhà/lớp học), KHÔNG mở
# cho mạng Public.
# =============================================================================

$ErrorActionPreference = 'Stop'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[LỖI] Phải chạy PowerShell bằng quyền Administrator." -ForegroundColor Red
    exit 1
}

# Đọc cổng từ .env (nếu không đọc được thì dùng mặc định)
$envPath = Join-Path $PSScriptRoot '..\.env'
$localPort = 8883; $serverPort = 8884
if (Test-Path $envPath) {
    foreach ($line in Get-Content $envPath) {
        if ($line -match '^LOCAL_PORT=(\d+)')  { $localPort  = [int]$Matches[1] }
        if ($line -match '^SERVER_PORT=(\d+)') { $serverPort = [int]$Matches[1] }
    }
    Write-Host "[OK] Đọc cổng từ .env: local=$localPort, server=$serverPort"
} else {
    Write-Host "[CẢNH BÁO] Không thấy .env, dùng cổng mặc định 8883/8884"
}

foreach ($p in @($localPort, $serverPort)) {
    $name = "SmartHome MQTT TCP $p"
    if (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue) {
        Write-Host "[ĐÃ CÓ] $name"
    } else {
        New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $p -Profile Private | Out-Null
        Write-Host "[ĐÃ THÊM] $name (chỉ profile Private)"
    }
}

Write-Host ""
Write-Host "Trạng thái các mạng đang kết nối (cần là Private):"
Get-NetConnectionProfile | ForEach-Object {
    $color = if ($_.NetworkCategory -eq 'Private') { 'Green' } else { 'Yellow' }
    Write-Host ("  - {0} : {1}" -f $_.Name, $_.NetworkCategory) -ForegroundColor $color
}
Write-Host ""
Write-Host "Nếu mạng đang là Public, đổi sang Private (thay '<Tên WiFi>'):"
Write-Host "  Set-NetConnectionProfile -Name '<Tên WiFi>' -NetworkCategory Private"
Write-Host ""
Write-Host "Kiểm tra cổng đã publish bởi Docker:" 
Write-Host "  docker ps --format '{{.Names}}`t{{.Ports}}'"