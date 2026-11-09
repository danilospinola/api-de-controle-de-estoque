<#
.SYNOPSIS
    Carrega no terminal atual as variáveis do perfil "rds" a partir da stack CloudFormation.

.DESCRIPTION
    Lê os outputs da stack (endpoint, porta, banco e ARN do segredo) e o usuário e a senha
    mestre no AWS Secrets Manager. A senha fica apenas na sessão atual do PowerShell: nada é
    gravado em disco nem exibido. Baixa o bundle de CA do RDS para certs\ se ele não existir.

    Execute a partir da raiz do projeto, com dot-source (o ponto inicial é obrigatório para
    que as variáveis fiquem na sua sessão):

        . .\infra\rds-env.ps1 -AwsProfile <seu-perfil>
        .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=rds"
#>
param(
    [string]$StackName = "controle-estoque-dev",
    [string]$Region = "us-east-2",
    [string]$AwsProfile = $env:AWS_PROFILE
)

$awsArgs = @("--region", $Region, "--output", "json")
if ($AwsProfile) { $awsArgs += @("--profile", $AwsProfile) }

# Out-String: no Windows PowerShell 5.1 o ConvertFrom-Json não junta JSON de várias linhas.
$stackJson = aws cloudformation describe-stacks --stack-name $StackName @awsArgs | Out-String
if ($LASTEXITCODE -ne 0) { throw "Não foi possível ler a stack '$StackName' em $Region." }
$saidas = @{}
foreach ($o in ($stackJson | ConvertFrom-Json).Stacks[0].Outputs) { $saidas[$o.OutputKey] = $o.OutputValue }

$segredoJson = aws secretsmanager get-secret-value --secret-id $saidas["MasterUserSecretArn"] @awsArgs | Out-String
if ($LASTEXITCODE -ne 0) { throw "Não foi possível ler o segredo do banco no Secrets Manager." }
$credenciais = ($segredoJson | ConvertFrom-Json).SecretString | ConvertFrom-Json

$env:DB_HOST = $saidas["DatabaseEndpoint"]
$env:DB_PORT = $saidas["DatabasePort"]
$env:DB_NAME = $saidas["DatabaseName"]
$env:DB_USERNAME = $credenciais.username
$env:DB_PASSWORD = $credenciais.password
Remove-Variable segredoJson, credenciais

$bundle = Join-Path (Get-Location) "certs\global-bundle.pem"
if (-not (Test-Path $bundle)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $bundle) | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri "https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem" -OutFile $bundle
}

Write-Host "Perfil rds pronto: DB_HOST=$env:DB_HOST DB_NAME=$env:DB_NAME DB_USERNAME=$env:DB_USERNAME (senha carregada, não exibida)."
