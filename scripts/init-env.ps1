#requires -Version 7.2
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    throw '.env 已存在，为避免覆盖已有密码，脚本已停止。'
}
$content = Get-Content -LiteralPath (Join-Path $projectRoot '.env.example') -Raw
foreach ($name in @('DATABASE_PASSWORD', 'ROOT_PASSWORD', 'QDRANT_KEY', 'ALICE_PASSWORD', 'BOB_PASSWORD')) {
    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
    $secret = [Convert]::ToHexString($bytes).ToLowerInvariant()
    $content = $content.Replace("<GENERATE_LOCAL_$name>", $secret)
}
Set-Content -LiteralPath $envFile -Value $content -Encoding utf8NoBOM
Write-Output '已创建 .env 并生成随机本地凭据。文件已加入 .gitignore，请勿提交。'
Write-Output '请自行填写 .env 中的 ZHIPU_API_KEY 和 DEEPSEEK_API_KEY，云服务密钥不会自动生成。'

