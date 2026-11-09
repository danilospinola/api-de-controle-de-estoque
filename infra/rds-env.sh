#!/usr/bin/env bash
# Carrega no shell atual as variáveis do perfil "rds" a partir da stack CloudFormation.
#
# Lê os outputs da stack (endpoint, porta, banco e ARN do segredo) e o usuário e a senha mestre
# no AWS Secrets Manager. A senha fica apenas na sessão atual do shell: nada é gravado em disco
# nem exibido. Baixa o bundle de CA do RDS para certs/ se ele não existir.
#
# Uso, a partir da raiz do projeto ("source" é obrigatório para as variáveis ficarem no shell;
# o perfil da AWS CLI vem de AWS_PROFILE, se definido):
#   source infra/rds-env.sh [stack] [regiao]
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=rds

(return 0 2>/dev/null) || { echo "Use: source infra/rds-env.sh [stack] [regiao]" >&2; exit 1; }

_rds_stack="${1:-controle-estoque-dev}"
_rds_regiao="${2:-us-east-2}"

_rds_saida() {
  aws cloudformation describe-stacks --stack-name "$_rds_stack" --region "$_rds_regiao" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue | [0]" --output text
}

_rds_campo_json() {  # lê um campo do JSON recebido na entrada padrão, sem depender de jq
  local py
  py="$(command -v python3 || command -v python)" || { echo "python3 não encontrado" >&2; return 1; }
  "$py" -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

_rds_arn="$(_rds_saida MasterUserSecretArn)" || return 1
_rds_segredo="$(aws secretsmanager get-secret-value --secret-id "$_rds_arn" --region "$_rds_regiao" \
  --query SecretString --output text)" || return 1

export DB_HOST="$(_rds_saida DatabaseEndpoint)"
export DB_PORT="$(_rds_saida DatabasePort)"
export DB_NAME="$(_rds_saida DatabaseName)"
export DB_USERNAME="$(printf '%s' "$_rds_segredo" | _rds_campo_json username)"
export DB_PASSWORD="$(printf '%s' "$_rds_segredo" | _rds_campo_json password)"
unset _rds_segredo _rds_arn

if [ ! -f certs/global-bundle.pem ]; then
  mkdir -p certs
  curl -fsS -o certs/global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem || return 1
fi

echo "Perfil rds pronto: DB_HOST=$DB_HOST DB_NAME=$DB_NAME DB_USERNAME=$DB_USERNAME (senha carregada, não exibida)."
unset -f _rds_saida _rds_campo_json
unset _rds_stack _rds_regiao
