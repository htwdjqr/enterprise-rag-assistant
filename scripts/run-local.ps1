#requires -Version 7.2
param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$backendRoot = Join-Path $projectRoot 'backend'
$envFile = Join-Path $projectRoot '.env'
if (!(Test-Path -LiteralPath $envFile)) { throw '请先运行 scripts/init-env.ps1' }

# 仅加载项目自己的 .env 到当前进程，不修改机器级环境变量，也不输出凭据。
Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
foreach ($keyName in @('ZHIPU_API_KEY', 'DEEPSEEK_API_KEY')) {
    $keyValue = [Environment]::GetEnvironmentVariable($keyName, 'Process')
    if ([string]::IsNullOrWhiteSpace($keyValue) -or $keyValue.StartsWith('<')) {
        throw "请先在 .env 中填写 $keyName，不能使用占位符。"
    }
}
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $env:Path
}
if (!$env:DB_URL) {
    $env:DB_URL = "jdbc:mysql://localhost:$($env:MYSQL_PORT)/enterprise_rag?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED"
}
Push-Location $backendRoot
try {
    & mvn -B -ntp spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw '应用启动失败，请检查 JDK 21、MySQL、Qdrant 以及智普/DeepSeek API 配置。' }
} finally { Pop-Location }

