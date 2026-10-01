$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = '净启开发者平台'
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { Write-Host '未找到 Python。请安装 Python 3.9 及以上版本后重试。' -ForegroundColor Yellow; return }
Write-Host '首次运行时 Windows 可能询问是否允许 Python 访问网络：请勾选“专用网络”并允许，否则手机无法连接。'
& $python.Source "$PSScriptRoot\jingqi_server.py" @args
