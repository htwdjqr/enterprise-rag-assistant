#requires -Version 7.2
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$settings = @{}
Get-Content -LiteralPath (Join-Path $projectRoot '.env') | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}

function New-ApiClient([string]$Username, [string]$Password) {
    $credentials = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Username}:$Password"))
    $headers = @{ Authorization = "Basic $credentials" }
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Invoke-RestMethod -Uri "$BaseUrl/api/csrf" -Headers $headers -WebSession $session
    $headers[$csrf.headerName] = $csrf.token
    return @{ Headers = $headers; WebSession = $session }
}

function Invoke-JsonApi($Client, [string]$Method, [string]$Path, $Body) {
    $parameters = @{
        Uri = "$BaseUrl$Path"; Method = $Method; Headers = $Client.Headers; WebSession = $Client.WebSession
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
    }
    Invoke-RestMethod @parameters
}

$alice = New-ApiClient 'alice' $settings.BOOTSTRAP_ALICE_PASSWORD
$bob = New-ApiClient 'bob' $settings.BOOTSTRAP_BOB_PASSWORD
$kb = Invoke-JsonApi $alice 'Post' '/api/knowledge-bases' @{ name = '员工手册学习库'; description = '演示资料，非真实企业制度' }
Write-Output "1. 已创建知识库：$($kb.id)"

$file = Get-Item -LiteralPath (Join-Path $projectRoot 'docs/examples/employee-handbook.md')
$document = Invoke-RestMethod -Uri "$BaseUrl/api/knowledge-bases/$($kb.id)/documents" -Method Post `
    -Headers $alice.Headers -WebSession $alice.WebSession -Form @{ file = $file }
Write-Output "2. 文档已上传，正在解析与向量化：$($document.id)"

$deadline = [DateTime]::UtcNow.AddMinutes(5)
do {
    $document = Invoke-JsonApi $alice 'Get' "/api/documents/$($document.id)" $null
    if ($document.status -eq 'FAILED') { throw $document.errorMessage }
    if ($document.status -eq 'READY') { break }
    if ([DateTime]::UtcNow -gt $deadline) { throw '文档处理超过 5 分钟，请查询文档状态和服务日志。' }
    Start-Sleep -Seconds 2
} while ($true)
Write-Output "3. 文档已就绪，共 $($document.chunkCount) 个文本块。"

Invoke-JsonApi $alice 'Post' "/api/knowledge-bases/$($kb.id)/members" @{ username = 'bob' }
Write-Output '4. 已授权 bob 读取此知识库。'
$answer = Invoke-JsonApi $bob 'Post' "/api/knowledge-bases/$($kb.id)/questions" @{ question = '出差住宿每天最多报销多少钱？需要什么材料？' }
$answer | ConvertTo-Json -Depth 10
Write-Output "知识库和文档已保留，便于继续学习。文档删除接口：DELETE /api/documents/$($document.id)"

