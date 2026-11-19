# sign-manifest.ps1 — подписывает манифест обновления (RSA-2048, PKCS#1 v1.5, SHA-256).
#
# Использование:
#   .\sign-manifest.ps1 -Jar D:\java-decompiler\build\libs\java-decompiler-0.1.0-all.jar -Version 0.1.1 -OutDir .\dist-update
#
# Приватный ключ по умолчанию: %USERPROFILE%\.unflatten-update-key.xml
# (генерируется один раз, в репозиторий и в дистрибутив не попадает).

param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$KeyPath = (Join-Path $env:USERPROFILE ".unflatten-update-key.xml"),
    [string]$OutDir = "."
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Jar)) { throw "jar не найден: $Jar" }
if (-not (Test-Path -LiteralPath $KeyPath)) { throw "приватный ключ не найден: $KeyPath" }

$sha = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $Jar).Length

# Канонический вид подписи — ровно в этом порядке и без пробелов.
$payload = "$Version|$sha|$size"

$rsa = New-Object System.Security.Cryptography.RSACryptoServiceProvider
$rsa.PersistKeyInCsp = $false
$rsa.FromXmlString((Get-Content -LiteralPath $KeyPath -Raw))

$data = [Text.Encoding]::UTF8.GetBytes($payload)
$sig = $rsa.SignData($data, [System.Security.Cryptography.CryptoConfig]::MapNameToOID("SHA256"))
$sigB64 = [Convert]::ToBase64String($sig)

# Публичная часть — для сверки с константой в лоадере.
$pub = $rsa.ExportParameters($false)
$modB64 = [Convert]::ToBase64String($pub.Modulus)
$rsa.Clear()

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$manifest = [ordered]@{
    jar_version = $Version
    sha256      = $sha
    size        = $size
    url         = "/latest.jar"
    sig         = $sigB64
}
$json = $manifest | ConvertTo-Json -Compress

Set-Content -LiteralPath (Join-Path $OutDir "manifest.json") -Value $json -Encoding UTF8 -NoNewline
Set-Content -LiteralPath (Join-Path $OutDir "version.txt") -Value $Version -Encoding ASCII -NoNewline
Set-Content -LiteralPath (Join-Path $OutDir "manifest.pubkey.txt") -Value $modB64 -Encoding ASCII

Write-Host "payload : $payload"
Write-Host "manifest: $(Join-Path $OutDir 'manifest.json')"
Write-Host "version : $Version"
