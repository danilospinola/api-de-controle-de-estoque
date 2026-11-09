# Controle de Estoque

API REST em **Java 17 + Spring Boot 4 (MVC)** para controle de estoque de produtos, com saldos
**total, disponível, reservado e bloqueado**, reservas e bloqueios identificáveis e liberáveis, e
garantia de consistência sob concorrência. Persistência em **PostgreSQL (Amazon RDS)**.

> **Demonstração online (temporária):** https://d2a24d0sjwri8v.cloudfront.net/swagger-ui.html
>
> Swagger UI da API rodando na AWS, gravando no Amazon RDS. Fica no ar só durante o processo seletivo.
> Para rodar localmente, sem nenhuma infraestrutura, veja a seção 6.

### Guia rápido para a avaliação

- **Testar sem instalar nada:** abra a demonstração; a página inicial do Swagger tem um roteiro de um minuto.
- **Rodar localmente** (JDK 17+, sem banco externo): `./mvnw spring-boot:run` e abra
  `http://localhost:8080`. **Testes:** `./mvnw test` (97 testes).

| O que o case pede | Onde está |
|---|---|
| Identificação do produto e quantidades total, disponível, reservada e bloqueada | `Produto` (SKU único + quatro saldos, invariante `total = disponível + reservada + bloqueada`); `GET /api/produtos/{sku}` |
| Entrada; saída só com disponível suficiente | `POST /api/produtos/{sku}/entradas` e `/saidas`; regra em `Produto.saida` |
| Reserva e bloqueio identificáveis, consultáveis e liberáveis | `Reserva` e `Bloqueio` com `id`; `GET /api/reservas/{id}`, `GET /api/bloqueios/{id}` e as rotas `/liberacao` |
| Várias reservas e bloqueios simultâneos, de solicitações diferentes | premissa 5 (seção 4); testes de concorrência (seção 6) |
| Nada acima do disponível e nenhuma operação inválida deixando o estoque inconsistente | domínio valida antes de alterar, transação única, lock otimista com retry e CHECKs no banco (seção 3) |
| Outras regras, validações e premissas documentadas | seções 3 e 4 |
| README: visão geral, arquitetura, decisões, premissas, limitações, execução e produção | seções 1 a 7 |
| Testes automatizados | seção 6 (unitários, integração com concorrência real e contrato HTTP) |
| Uso de IA: prompts, onde ajudou, sugestões modificadas e erros | seção 8 |
| Cinco aspectos de engenharia | seção 9 |

Além do pedido: efetivação de reserva (consumo), reserva de pedido com vários produtos (tudo ou nada), SKU
único como chave de negócio e a API publicada na AWS para a avaliação.

---

## 1. Visão geral da solução

Cada produto mantém um saldo consolidado que obedece sempre à invariante:

```
quantidadeTotal = quantidadeDisponivel + quantidadeReservada + quantidadeBloqueada   (todas ≥ 0)
```

O **produto é identificado pelo SKU** em todas as rotas (ex.: `/api/produtos/TEC-LOGI-K120`; maiúsculas e
minúsculas são equivalentes). Reservas e bloqueios são identificados pelo `id` (UUID) devolvido ao criá-los.

| Funcionalidade | Endpoint | Efeito no saldo |
|---|---|---|
| Cadastrar produto (`sku` único + `nome` + `marca` opcional + quantidade inicial) | `POST /api/produtos` | total = disponível = quantidade inicial |
| **Consultar** | `GET /api/produtos/{sku}` | — |
| **Entrada** | `POST /api/produtos/{sku}/entradas` | total ↑, disponível ↑ |
| **Saída** | `POST /api/produtos/{sku}/saidas` | total ↓, disponível ↓ (só se houver disponível) |
| **Reservar** | `POST /api/produtos/{sku}/reservas` | disponível ↓, reservada ↑ |
| **Reservar pedido (vários produtos)** | `POST /api/pedidos/{pedidoId}/reservas` (itens com `sku` e `quantidade`) | em cada produto: disponível ↓, reservada ↑ (tudo ou nada) |
| Listar reservas do pedido | `GET /api/pedidos/{pedidoId}/reservas?status=ATIVA` | — |
| Consultar reserva | `GET /api/reservas/{id}` | — |
| Listar reservas | `GET /api/produtos/{sku}/reservas?status=ATIVA` | — |
| **Liberar reserva** | `POST /api/reservas/{id}/liberacao` | reservada ↓, disponível ↑ |
| Efetivar reserva | `POST /api/reservas/{id}/efetivacao` | reservada ↓, total ↓ (consumo) |
| **Bloquear** | `POST /api/produtos/{sku}/bloqueios` | disponível ↓, bloqueada ↑ |
| Consultar bloqueio | `GET /api/bloqueios/{id}` | — |
| Listar bloqueios | `GET /api/produtos/{sku}/bloqueios?status=ATIVO` | — |
| **Liberar bloqueio** | `POST /api/bloqueios/{id}/liberacao` | bloqueada ↓, disponível ↑ |

Ciclos de vida:

```
Reserva:  ATIVA ──liberar──▶ LIBERADA        Bloqueio:  ATIVO ──liberar──▶ LIBERADO
            └────efetivar──▶ EFETIVADA
```

Estados finais (`LIBERADA`, `EFETIVADA`, `LIBERADO`) não aceitam novas transições.

Documentação interativa (Swagger UI): `http://localhost:8080/swagger-ui.html` na execução local, ou o
link da demonstração online acima. A raiz (`/`) também redireciona para o Swagger, que está organizado na
ordem do fluxo (**1. Produtos → 2. Movimentações → 3. Reservas → 4. Bloqueios**). A página inicial traz um
roteiro de teste de um minuto, e cada operação descreve o efeito no saldo e os erros que pode devolver (400,
404, 409, 422 e 500), com exemplos reais em português.

---

## 2. Arquitetura / estrutura

Arquitetura em camadas (MVC) com **modelo de domínio rico**: as regras de negócio vivem nas
entidades; a camada de serviço apenas orquestra transação, carregamento e persistência.

```
src/main/java/com/example/controledeestoque
├── controller/        # REST (MVC): rotas, validação de payload, tradução de erros
│   ├── dto/           # Records de entrada (Bean Validation) e saída — entidades nunca são expostas
│   └── GlobalExceptionHandler   # Exceções → RFC 9457 (application/problem+json)
├── service/
│   ├── EstoqueService                 # Casos de uso
│   └── ExecutorTransacionalComRetry   # Transação + retry em conflito de lock otimista
├── domain/            # Produto, Reserva, Bloqueio, enums de status — regras e invariantes
├── repository/        # Spring Data JPA
├── exception/         # Exceções de negócio
└── config/            # Clock injetável, contrato OpenAPI (servidor relativo) e leitura estrita do JSON
src/main/resources
├── application.yaml                 # Perfil padrão (H2) e perfil "rds" (PostgreSQL)
└── db/migration/V1, V2, V3          # Schema versionado (Flyway): tabelas e CHECKs, pedido preenchido, SKU único
infra
├── rds-postgresql.yaml              # CloudFormation: RDS PostgreSQL, security group, subnet e parameter group
├── app-ec2.yaml                     # CloudFormation: API publicada (EC2 t4g.micro + CloudFront + bucket do jar)
└── rds-env.ps1 / rds-env.sh         # Carregam endpoint e credenciais (Secrets Manager) no terminal
```

Ambiente de demonstração na AWS (duas stacks, `us-east-2`):

```
Avaliador ──HTTPS──▶ CloudFront ──HTTP:80──▶ EC2 t4g.micro (app, perfil rds) ──TLS──▶ RDS PostgreSQL
                                             SG: porta 80 só do CloudFront          SG: 5432 só da EC2
                                             senha lida no Secrets Manager          (e do IP do dev)
```

Fluxo de uma escrita (ex.: reservar):

```
HTTP → ProdutoController (valida DTO)
     → EstoqueService.reservar
        → ExecutorTransacionalComRetry (abre transação)
           → carrega Produto pelo SKU (com version)
           → Reserva.criar(produto, ...)  ← regra: quantidade ≤ disponível
           → flush: UPDATE produto ... WHERE id=? AND version=?   + INSERT reserva
        ← conflito de versão? nova transação, relê o saldo, reavalia a regra
     ← 200 OK + reserva criada no corpo (com o id)
```

---

## 3. Principais decisões técnicas

| Decisão | Motivo | Alternativa considerada |
|---|---|---|
| **Saldo consolidado no `Produto` + registros de `Reserva`/`Bloqueio`** | Consulta do saldo é O(1) e a validação "cabe no disponível?" é feita sobre uma única linha, que é o ponto de serialização da concorrência. As reservas/bloqueios dão rastreabilidade e permitem liberar por id. | Calcular saldo somando movimentações (event sourcing/ledger): melhor auditoria, mas consultas e validação mais caras e complexas para o escopo. |
| **Modelo rico, sem setters de quantidade** | Impossível alterar um saldo sem passar pela regra. Pré-condições são validadas **antes** de qualquer mutação, então uma operação inválida nunca deixa o objeto em estado intermediário. | Regras no service com setters: mais fácil de violar acidentalmente. |
| **Lock otimista (`@Version`) + retry** | Não segura locks no banco entre leitura e escrita; escala bem com baixa/média disputa. O retry abre **nova transação** a cada tentativa (via `TransactionTemplate`), relendo o saldo e reavaliando as regras. | Lock pessimista (`SELECT ... FOR UPDATE`): melhor para produtos extremamente disputados (evita tempestade de retries), ao custo de bloquear a linha e risco de deadlock. Trocar é localizado (um método no repositório). |
| **Retry explícito em vez de `@Retryable` + `@Transactional`** | Com anotações, a ordem dos proxies decide se o retry fica fora ou dentro da transação; se ficar dentro, repete numa transação já marcada para rollback. O componente explícito torna isso evidente e testável. | Spring Retry / `@Retryable` do Spring Framework 7. |
| **CHECK constraints no banco** (invariante e não-negatividade) | Defesa em profundidade: mesmo um bug, script manual ou outro serviço não consegue gravar estado inconsistente. Há teste provando isso. | Confiar só na aplicação. |
| **Flyway + `ddl-auto=validate`** | Schema versionado, revisável e reproduzível; Hibernate só valida o mapeamento. | `ddl-auto=update`: inadequado para produção. |
| **H2 (modo PostgreSQL) no perfil local/testes; PostgreSQL no perfil `rds`** | Executa e testa sem infraestrutura. A mesma migration roda nos dois bancos. | Testcontainers com PostgreSQL real (ver Limitações; não havia Docker no ambiente de desenvolvimento). |
| **Erros RFC 9457 em português, com status semânticos** | 400 payload ou formato inválido (indicando o campo, o tipo ou os valores aceitos) · 404 inexistente · 405 método não permitido · 413 corpo acima do limite · 422 regra de negócio (estoque insuficiente, transição inválida) · 409 conflito de concorrência não resolvido · 500 erro inesperado (mensagem genérica com um identificador que também vai para o log, sem detalhes internos). As mensagens de validação são explícitas em português, então não dependem do idioma do servidor. O cliente diferencia "corrija o pedido" de "tente de novo". | 400 genérico para tudo; responder 200 também nos erros (descartado: clientes, monitoramento e health check passariam a tratar erro como sucesso). |
| **Sucesso sempre 200, inclusive nos cadastros** | Decisão do projeto: toda resposta de sucesso usa o mesmo código e traz o recurso no corpo (o `id` da reserva ou do bloqueio criado vem no corpo). A mudança fica só na camada web (domínio, banco e infraestrutura não são afetados). | `201 Created` + `Location`, a convenção REST para criação. Ela distingue "criei agora" de "já existia", o que pesará quando houver idempotência e SKU (409). |
| **SKU único como chave de negócio do produto** | Impede que o mesmo item exista em dois registros com saldos separados. O SKU é normalizado para maiúsculas, então a caixa não cria duplicatas. Um SKU repetido responde 409 e a mensagem aponta a entrada de estoque desse SKU; em cadastros simultâneos, a constraint única do banco decide quem vence e o outro recebe o mesmo 409. | Nome único (recusaria produtos diferentes com o mesmo nome); cadastro que soma unidades a um SKU existente (*upsert*), que misturaria cadastro com entrada de estoque sem o cliente perceber. |
| **SKU como identificador do produto nas rotas; UUID só no banco** | É o código que quem opera o estoque conhece: as rotas ficam legíveis (`/api/produtos/TEC-LOGI-K120/reservas`), quem integra não precisa guardar um UUID nem consultar antes de operar, e a busca separada por SKU deixou de ser necessária. O UUID continua como chave primária e nas chaves estrangeiras de reserva e bloqueio, então a troca não exigiu migration e não altera o desempenho dos *joins*. A busca pelo SKU usa o índice da constraint única. Reservas e bloqueios mantêm o UUID, porque não têm código de negócio. **Trade-offs aceitos:** (1) o SKU passa a ser um contrato público e precisa ser **imutável**: corrigir um SKU digitado errado quebraria URLs e integrações, ao passo que o UUID nunca muda (hoje não existe edição de SKU); (2) o SKU aparece em URLs, logs de acesso e histórico do navegador (não é dado sensível, mas fica exposto); (3) as respostas mostram o `sku` em vez do `produtoId`, então reservas e bloqueios precisam carregar o produto junto (`@EntityGraph` nas consultas, porque a resposta é montada fora da transação, com `open-in-view=false`); (4) as rotas de produto não validam o formato do SKU no endereço: um SKU fora do formato simplesmente não existe e responde 404. | Manter o UUID nas rotas com uma busca por SKU (era o desenho anterior: estável mesmo se o SKU mudasse, mas obrigava o cliente a descobrir o UUID antes de operar); aceitar os dois no mesmo endereço (ambíguo, porque um SKU pode ter o formato de um UUID); rota com prefixo, como `/api/produtos/sku/{sku}` (mais explícita, porém mais longa e duplicaria todas as rotas). |
| **Reserva de pedido com vários produtos, tudo ou nada** | Um pedido real tem vários itens. Reservá-los um a um deixaria o pedido reservado pela metade quando um item falha. Todos os itens entram numa única transação, com os produtos carregados numa só consulta: se um falhar, nada é reservado e a mensagem diz qual SKU faltou. Os itens são processados em ordem de SKU e o Hibernate ordena os UPDATEs pela chave primária (`order_updates`), então transações concorrentes travam as linhas sempre na mesma ordem e não entram em deadlock; o retry também cobre conflitos de lock. | Várias chamadas à rota por produto, com compensação pelo cliente (liberar o que já foi reservado), frágil e não atômica. |
| **Contrato JSON estrito e limites de entrada** | Num estoque, aceitar um valor "quase certo" é pior que recusar: com os padrões do Jackson, `"quantidade": 2.7` virava 2, `"3"` virava 3, um erro de digitação como `quantidadeIncial` era ignorado (o produto nascia com 0 unidades) e uma chave repetida valia o último valor. Agora tudo isso responde 400 com o campo e, no caso do campo desconhecido, a lista dos campos aceitos. Os textos (`nome`, `marca`, `pedidoId`, `motivo`) não aceitam caracteres de controle (quebra de linha, tabulação, caractere nulo), que quebram exibição, permitem forjar linhas de log e, no caso do nulo, eram recusados pelo PostgreSQL com um 409 enganoso. O corpo tem limite de 100 KB (413), porque a API é pública e a instância da demo é pequena. O `pedidoId` do endereço segue as mesmas regras do corpo. **Trade-off:** um cliente que envie campos extras (por exemplo, de uma versão mais nova do contrato) passa a receber 400; mudanças de contrato exigem versionar a API. | Padrões do Jackson (tolerantes) com validação só no domínio; `@Pattern` para caracteres de controle (descartado: a regex que aceita espaços nas pontas fica ambígua e pode levar tempo exponencial, ReDoS). |
| **DTOs separados das entidades** | Contrato da API estável e independente do modelo de persistência; evita lazy loading na serialização (`open-in-view=false`). | Serializar entidades diretamente. |
| **`Clock` injetável, UTC, na precisão do banco** | Datas determinísticas nos testes e independentes do fuso do servidor. O instante é cortado em microssegundos, a precisão da coluna: sem isso, a criação devolvia nanossegundos que o banco descarta, e a mesma reserva aparecia com duas datas. As listagens desempatam pela ordem do SKU e do id, então a ordem é sempre a mesma (os itens de um pedido são criados no mesmo instante). | `LocalDateTime.now()` direto. |
| **Infraestrutura como código (CloudFormation), implantada pelo MCP da AWS** | O template em `infra/` é versionado e revisável: quem avalia lê e reproduz a infraestrutura, as decisões ficam registradas no próprio template e a remoção é um `delete-stack`. O deploy passa por *change set*, com a validação pré-deploy do CloudFormation antes de qualquer recurso ser criado. | Criar os recursos com comandos avulsos (console/CLI): mais rápido da primeira vez, mas não reproduzível nem auditável. CDK: abstrações úteis em projetos maiores, excesso para duas stacks pequenas. |
| **API publicada para os avaliadores: EC2 `t4g.micro` + CloudFront** | Os avaliadores usam o Swagger e gravam no RDS sem receber senha de banco nem acesso à conta AWS. A `t4g.micro` é a menor instância aceita pelo plano Free (as `nano` não são, e 512 MiB é pouco para Spring Boot). O CloudFront dá HTTPS gratuito e permite que a EC2 aceite tráfego só dele. A EC2 lê a senha no Secrets Manager pelo papel IAM. | Abrir o banco para os IPs dos avaliadores (exige compartilhar a senha); GitHub Pages (só hospeda o Swagger estático, sem executar chamadas); ECS Fargate + ALB (mais robusto, porém mais caro e complexo para uma demonstração). |
| **RDS mínimo e temporário** (PostgreSQL 18.6, `db.t4g.micro`, Single-AZ) | Menor custo elegível ao plano Free da conta (cerca de US$ 18/mês, pagos com créditos), suficiente para demonstrar a solução durante o processo seletivo. Mantém o que não custa nada a mais: criptografia, TLS obrigatório, proteção contra exclusão e senha gerenciada no Secrets Manager. Sem backup: os dados são descartáveis. | Multi-AZ (bloqueado no plano Free) e a configuração completa de produção (seção 7). |

---

## 4. Premissas consideradas

1. **`quantidade` foi adicionada a `Reserva` e `Bloqueio`.** Sem ela não é possível devolver as
   unidades corretas na liberação.
2. **Bloqueio só incide sobre unidades disponíveis**, como pede o enunciado. Unidades reservadas
   não podem ser bloqueadas diretamente (seria preciso liberar a reserva antes).
3. **Saída consome apenas o disponível.** Unidades reservadas saem do estoque pela **efetivação
   da reserva** (`EFETIVADA`), que dá baixa no total.
4. **`pedidoId` é uma referência externa** (sistema de pedidos), armazenada como `String`, sem FK
   física, pois não existe entidade `Pedido` neste serviço. É obrigatório, inclusive no bloqueio, porque o
   modelo pedido associa todo bloqueio a uma solicitação. `motivo` é opcional. Os textos (`nome`, `marca`,
   `pedidoId`, `motivo`) são gravados sem espaços nas pontas, não aceitam caracteres de controle e são
   validados na API e no domínio; o preenchimento também é garantido pelo banco.
5. **Um produto pode ter várias reservas/bloqueios ativos**, inclusive do mesmo pedido.
6. **Reserva e bloqueio são integrais**: liberação parcial não é suportada (libera-se a quantidade inteira).
7. **O produto é identificado pelo negócio por um SKU único.** O `sku` é obrigatório, tem até 50
   caracteres e aceita letras sem acento, números e hífens entre eles (ex.: `TEC-LOGI-K120`). Ele é gravado
   em maiúsculas, então `tec-logi-k120` e `TEC-LOGI-K120` são o mesmo produto. Cadastrar um SKU que já existe
   responde **409**; novas unidades entram pela entrada de estoque desse SKU. O `nome`
   (obrigatório, até 150 caracteres) e a `marca` (opcional, até 100) são descritivos e não únicos, porque
   marcas diferentes podem usar o mesmo nome. **O SKU identifica o produto em todas as rotas e é imutável**;
   o UUID do produto é só a chave técnica do banco e não aparece na API.
   As três regras são validadas na API, no domínio e no banco (constraint única e CHECKs da migration `V3`).
   Os produtos que já existiam no RDS receberam um SKU provisório, `LEGADO-<id>`.
8. **Quantidades são números inteiros e positivos**, até 2.147.483.647 unidades por produto (limite de
   `Integer`). Decimais (`2.7`, `2.0`), notação científica (`1e1`) e texto (`"3"`) são recusados com 400, em
   vez de arredondados. Uma entrada que ultrapassaria o limite é rejeitada como regra de negócio (422), sem
   alterar o saldo.
9. **A infraestrutura na AWS é temporária.** Ela existe só durante o processo seletivo e os dados gravados
   no RDS são descartáveis. Por isso o ambiente prioriza custo baixo e simplicidade, não alta
   disponibilidade (ver limitações).
10. **Datas em UTC.** `dataCriacao` é um `LocalDateTime` (como no modelo pedido) gerado sempre em UTC por um
    `Clock` injetado, na precisão de microssegundos do banco; o contrato OpenAPI informa isso.
11. **Na reserva de pedido, cada SKU aparece uma única vez**, com a quantidade total; um SKU repetido
    na lista (mesmo em outra caixa, como `tec-01` e `TEC-01`) é rejeitado. O pedido tem de 1 a 100 itens,
    e todas as suas reservas recebem o mesmo instante de criação. A rota por produto continua disponível para
    reservas avulsas.

---

## 5. Limitações conhecidas

### Aplicação

- **Sem idempotência**: um retry do cliente após timeout pode criar uma segunda reserva.
- **Reservas não expiram**: uma reserva esquecida retém estoque indefinidamente.
- **Sem histórico/auditoria de movimentações** (entradas e saídas alteram o saldo sem registro próprio).
- **Testes em H2**, não em PostgreSQL real: o modo de compatibilidade cobre o usado aqui, mas
  isolamento e locking diferem em detalhes.
- **Lock otimista sob altíssima disputa** num único produto pode esgotar as tentativas (resulta em 409).
- **Sem autenticação/autorização** e sem paginação nas listagens.
- Sem edição de SKU, nome ou marca e sem exclusão de produto. A falta de edição do SKU é intencional: ele é o
  identificador nas rotas, e trocá-lo quebraria URLs e integrações. Um SKU cadastrado errado hoje não tem
  correção pela API (ver item 16 da seção 7).
- **O health check da EC2 chama `GET /api/produtos/00000000-0000-0000-0000-000000000000` e espera 404.** Com
  o SKU nas rotas, esse endereço passou a ser lido como um SKU inexistente e continua respondendo 404, então
  o health check segue válido sem alterar a stack; o nome do endereço só ficou menos intuitivo.
- **Sem log nem auditoria das operações.** O cadastro e as movimentações não geram linha de log, e o produto
  não tem data de criação. O access log do Tomcat e os logs de acesso do CloudFront estão desligados. Na AWS,
  o log da aplicação fica só no journal da EC2 (sem CloudWatch) e os logs do PostgreSQL não são exportados.
  O único rastro de um cadastro é a própria linha no banco.
- **Sem listagem de produtos** (`GET /api/produtos` responde 405) e sem health check dedicado (Actuator).
- **Lacunas de teste**: sem teste de API para o 409 de conflito esgotado e sem integração contínua (CI).

### Infraestrutura na AWS (ambiente temporário de avaliação)

O ambiente de [`infra/rds-postgresql.yaml`](infra/rds-postgresql.yaml) foi dimensionado para ficar de pé
apenas durante o processo seletivo. Ele **não** é um ambiente de produção:

- **Sem alta disponibilidade.** A instância é Single-AZ, porque o plano Free não permite Multi-AZ. Uma falha
  na zona de disponibilidade ou uma manutenção deixa o banco fora do ar.
- **Sem backup, por decisão.** Os backups automáticos estão desligados (`BackupRetentionPeriod: 0`), então
  não há *point-in-time restore* nem snapshot final ao remover a stack (`DeletionPolicy: Delete`). Uma falha
  da instância, a exclusão da stack ou uma atualização que substitua a instância apagam os dados sem cópia;
  isso é aceitável aqui porque os dados são descartáveis. Além disso, o plano Free limita a retenção de
  backup: o RDS rejeitou 7 dias no primeiro deploy. Ligar o backup depois exige o plano pago (ou uma
  retenção aceita pelo plano Free), e mudar a retenção de 0 para um valor positivo causa uma breve
  indisponibilidade.
- **Endpoint público, restrito a um único IP.** O security group só aceita a porta 5432 a partir do `/32`
  informado no deploy, e o TLS é obrigatório (`rds.force_ssl=1` no servidor, `sslmode=verify-full` na
  aplicação). Ainda assim o banco tem endereço na internet. Se o IP do desenvolvedor mudar, a conexão falha
  até a stack ser atualizada com o novo `AllowedCidr`.
- **A aplicação usa o usuário mestre.** Não há usuário de aplicação com privilégios mínimos: a aplicação e
  o Flyway compartilham a mesma credencial, com permissão de DDL.
- **Credencial em variável de ambiente.** O script `infra/rds-env.*` lê a senha do Secrets Manager e a
  deixa na sessão do terminal. O RDS rotaciona esse segredo automaticamente a cada 7 dias; como a aplicação só lê a senha
  na inicialização, depois de uma rotação é preciso recarregar as variáveis e reiniciar a aplicação.
- **Instância *burstable* pequena** (`db.t4g.micro`: 2 vCPUs, 1 GiB de RAM, créditos de CPU). Serve para
  testes funcionais, não para testes de carga: sob uso contínuo os créditos acabam e a CPU é limitada.
- **Criptografia com a chave gerenciada pela AWS** (`aws/rds`), sem chave KMS própria: não há controle
  sobre a política de acesso nem sobre a rotação da chave.
- **Observabilidade e auditoria mínimas.** Só o Database Insights Standard (7 dias). Os logs do PostgreSQL
  não vão para o CloudWatch, não há alarmes e a conta não tem trilha do CloudTrail, apenas o histórico de
  eventos de 90 dias.
- **Validação limitada do template.** Ele passou pelo `ValidateTemplate` e pela validação pré-deploy do
  CloudFormation, mas não por `cfn-lint` (lint local) nem por `cfn-guard` (políticas de segurança e
  compliance). Ver seção 7.
- **Região única** (`us-east-2`, a Região selecionada do projeto AWS), sem recuperação de desastre em outra
  Região.
- **Drift esperado na stack do banco.** A detecção de drift do CloudFormation acusa dois recursos, e nenhum é
  alteração manual: (1) o security group tem uma regra a mais, criada pela stack da API para liberar a EC2;
  (2) o parameter group aparece sem o `rds.force_ssl`, porque o valor 1 é o padrão do PostgreSQL 18 e o RDS
  não o registra como parâmetro definido pelo usuário. O TLS obrigatório foi verificado na prática: uma
  conexão sem criptografia é recusada pelo banco antes da autenticação. Ao atualizar a stack do banco (ex.:
  trocar o `AllowedCidr`), vale conferir se a regra da API continua no security group.
- **Custo contínuo enquanto existir**: cerca de US$ 18/mês (instância, disco gp3, IPv4 público e segredo).
  Esquecer a stack de pé consome os créditos. Ao remover a stack, nada fica para trás (ver o teardown na
  seção 6).

### API publicada para os avaliadores (stack `controle-estoque-app`)

- **API pública sem autenticação.** Qualquer pessoa com o link consegue ler e gravar dados. Não há login,
  WAF nem limite de requisições. Aceitável para dados descartáveis de demonstração, inaceitável em produção.
- **Instância única, sem alta disponibilidade.** Uma falha, manutenção ou reinício da EC2 deixa a API fora
  do ar até ela voltar. O serviço reinicia sozinho se o processo cair.
- **Rotação da senha causa uma breve indisponibilidade.** O RDS troca a senha a cada 7 dias. Um health check
  a cada 5 minutos detecta a falha de conexão e reinicia a aplicação, que relê a senha. Até lá, as chamadas
  que abrirem conexões novas falham.
- **Trecho CloudFront → EC2 em HTTP.** O avaliador fala HTTPS com o CloudFront, mas o CloudFront fala HTTP
  com a EC2 pela internet. Sem credenciais nesse trecho, mas sem criptografia.
- **A EC2 tem IP público.** Ela só aceita a porta 80 do *prefix list* gerenciado do CloudFront, mas esse
  prefix list vale para qualquer distribuição CloudFront, não só para esta. Uma EC2 privada atrás de
  *VPC origins* seria mais segura, mas exigiria NAT Gateway ou *VPC endpoints*, que custam mais que a
  própria aplicação.
- **Endereço da EC2 instável.** Parar e iniciar a instância muda o DNS público e quebra a origem do CloudFront
  até a stack ser atualizada. Um simples reinício (*reboot*) não muda.
- **Deploy manual.** Uma nova versão é publicada enviando o jar ao S3 e reiniciando o serviço; não há
  pipeline de CI/CD.
- **Custo adicional** de cerca de US$ 10,4/mês: instância (US$ 6,13), IPv4 público (US$ 3,65) e disco gp3
  de 8 GB (US$ 0,64). O CloudFront fica na cota gratuita. Somado ao banco, o ambiente custa cerca de
  US$ 28/mês dos créditos do plano Free.

---

## 6. Instruções para execução

### Pré-requisitos
- **JDK 17+** (o Maven Wrapper baixa o Maven automaticamente).

### Executar localmente (sem infraestrutura, H2 em memória)

```bash
./mvnw spring-boot:run
```

No Windows (PowerShell): `.\mvnw.cmd spring-boot:run`. A API sobe em `http://localhost:8080` e o Swagger UI em
`http://localhost:8080/swagger-ui.html`. Os dados são perdidos ao encerrar.

### Rodar os testes

```bash
./mvnw test
```

97 testes em quatro níveis:

| Classe | Tipo | O que garante |
|---|---|---|
| `ProdutoTest`, `ReservaBloqueioTest` | Unitário (domínio puro) | Regras de saldo, invariante, transições de status, rejeição sem efeito colateral, formato e normalização do SKU, textos sem caracteres de controle |
| `ExecutorTransacionalComRetryTest` | Unitário | Repete em conflito de versão, desiste após N tentativas, não repete erro de negócio |
| `EstoqueServiceIntegrationTest` | Integração (JPA + Flyway + banco) | Atomicidade, CHECK constraints, **concorrência real**: 20 reservas simultâneas sobre 50 unidades → exatamente 10 sucessos, zero overselling; liberação concorrente da mesma reserva devolve as unidades uma única vez; reserva de pedido com vários produtos tudo ou nada, inclusive pedidos concorrentes com os produtos em ordens invertidas, sem deadlock; SKU único mesmo com 8 cadastros simultâneos do mesmo SKU (só um vence, os outros recebem 409); todas as operações pelo SKU em qualquer caixa; consultas de reserva e bloqueio já trazem o produto para uso fora da transação; data devolvida na criação igual à gravada; ordem determinística das reservas do pedido |
| `EstoqueApiTest` | API (MockMvc) | Rotas por SKU (inclusive em minúsculas), status HTTP (200/400/404/405/409/422), erros em português (`ProblemDetail`) indicando o que corrigir, contrato OpenAPI com servidor relativo e sem rotas por UUID de produto; JSON estrito (decimal, texto em campo inteiro, campo desconhecido, chave repetida e corpo acima de 100 KB → 413) sem alterar o saldo; caracteres de controle e `pedidoId` do endereço com erro por campo |
| `GlobalExceptionHandlerTest` | Unitário | Erro inesperado: 500 em português, com identificador e sem detalhes internos |

### Exemplo rápido

```bash
# cadastrar
curl -X POST localhost:8080/api/produtos -H "Content-Type: application/json" -d '{"sku": "TEC-LOGI-K120", "nome": "Teclado mecanico", "marca": "Logitech", "quantidadeInicial": 10}'
# consultar pelo SKU (maiúsculas e minúsculas são equivalentes)
curl localhost:8080/api/produtos/tec-logi-k120
# reservar (a resposta traz o id da reserva)
curl -X POST localhost:8080/api/produtos/TEC-LOGI-K120/reservas -H "Content-Type: application/json" -d '{"pedidoId":"PED-1","quantidade":4}'
# reservar vários produtos de um pedido, tudo ou nada
curl -X POST localhost:8080/api/pedidos/PED-2/reservas -H "Content-Type: application/json" -d '{"itens":[{"sku":"TEC-LOGI-K120","quantidade":2}]}'
# liberar reserva
curl -X POST localhost:8080/api/reservas/{reservaId}/liberacao
```

No Git Bash do Windows, texto com acento digitado direto no comando não sai em UTF-8, e a API responde
400 informando que o corpo não é um JSON válido em UTF-8. Nesse caso, use o Swagger, envie o corpo por arquivo
(`--data-binary @produto.json`) ou use o PowerShell (`Invoke-RestMethod`).

### Executar contra o Amazon RDS for PostgreSQL (perfil `rds`)

A infraestrutura está em [`infra/rds-postgresql.yaml`](infra/rds-postgresql.yaml) (CloudFormation) e é
**temporária**: suba para a avaliação e remova depois. O template cria:

- uma instância RDS for PostgreSQL 18.6 (`db.t4g.micro`, Single-AZ, gp3 de 20 GB criptografado, sem backup);
- um parameter group com TLS obrigatório;
- um DB subnet group;
- um security group que só aceita conexões do seu IP.

A senha do usuário mestre é gerada pelo RDS e guardada no AWS Secrets Manager. O custo aproximado é de
US$ 18/mês. As limitações desse ambiente estão na seção 5.

**1. Criar a stack** (AWS CLI v2 autenticada, na Região do seu projeto AWS):

```bash
aws cloudformation create-stack --stack-name controle-estoque-dev --region us-east-2 \
  --template-body file://infra/rds-postgresql.yaml \
  --parameters ParameterKey=VpcId,ParameterValue=<vpc-id> \
               "ParameterKey=SubnetIds,ParameterValue=<subnet-a>\,<subnet-b>" \
               ParameterKey=AllowedCidr,ParameterValue=<seu-ip-publico>/32
aws cloudformation wait stack-create-complete --stack-name controle-estoque-dev --region us-east-2
```

Use a VPC padrão e subnets dela em pelo menos duas AZs. Seu IP público aparece em
`curl https://checkip.amazonaws.com`. A criação leva cerca de 10 minutos.

**2. Carregar endpoint e credenciais no terminal**, a partir da raiz do projeto. O script lê os outputs da
stack e o segredo no Secrets Manager, exporta as variáveis `DB_*` só na sessão atual (nada vai para disco)
e baixa o bundle de CA do RDS para `certs/`, se necessário:

```powershell
. .\infra\rds-env.ps1 -AwsProfile <seu-perfil>
```

```bash
source infra/rds-env.sh
```

**3. Subir a aplicação com o perfil `rds`:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=rds
```

No Windows: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=rds"`. O Flyway cria as tabelas na
primeira execução. A conexão usa `sslmode=verify-full`, que exige criptografia **e** valida o certificado e
o host, e o servidor recusa conexões sem TLS (`rds.force_ssl=1`).

**4. Remover tudo ao final (teardown).** Se a stack da API existir, remova-a **antes** (veja a próxima
seção), porque ela adiciona uma regra ao security group do banco. A instância tem proteção contra
exclusão, então ela precisa ser desligada antes de apagar a stack. Como não há backup nem snapshot final, nada fica para trás (e os dados
são apagados):

```bash
aws rds modify-db-instance --db-instance-identifier controle-estoque-dev-postgres \
  --no-deletion-protection --apply-immediately --region us-east-2
aws cloudformation delete-stack --stack-name controle-estoque-dev --region us-east-2
aws cloudformation wait stack-delete-complete --stack-name controle-estoque-dev --region us-east-2
```

Variáveis lidas pelo perfil `rds` (o script preenche as obrigatórias):

| Variável | Obrigatória | Padrão |
|---|---|---|
| `DB_HOST` | sim | — |
| `DB_USERNAME` / `DB_PASSWORD` | sim | — |
| `DB_PORT` | não | `5432` |
| `DB_NAME` | não | `estoque` |
| `DB_SSL_ROOT_CERT` | não | `./certs/global-bundle.pem` |
| `DB_POOL_SIZE` | não | `10` |

### Publicar a API para os avaliadores (EC2 + CloudFront)

O template [`infra/app-ec2.yaml`](infra/app-ec2.yaml) depende da stack do banco. Ele cria um bucket S3
privado para o jar, uma EC2 `t4g.micro` (Amazon Linux 2023, Java 21) com papel IAM de menor privilégio, os
security groups e uma distribuição CloudFront. A instância roda a aplicação como serviço `systemd` com o
perfil `rds`. A cada início, ela baixa o jar do S3 e lê a senha do banco no Secrets Manager. Não há SSH:
o acesso administrativo é pelo SSM Session Manager.

**1. Gerar o jar e criar a stack.** Os valores do banco vêm dos outputs da stack `controle-estoque-dev`.
Use a subnet da mesma AZ do RDS:

```bash
./mvnw package
aws ec2 describe-managed-prefix-lists --region us-east-2 \
  --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing \
  --query "PrefixLists[0].PrefixListId" --output text
aws cloudformation create-stack --stack-name controle-estoque-app --region us-east-2 \
  --template-body file://infra/app-ec2.yaml --capabilities CAPABILITY_IAM \
  --parameters ParameterKey=VpcId,ParameterValue=<vpc-id> \
               ParameterKey=SubnetId,ParameterValue=<subnet-da-az-do-rds> \
               ParameterKey=CloudFrontPrefixListId,ParameterValue=<pl-...> \
               ParameterKey=DatabaseSecurityGroupId,ParameterValue=<SecurityGroupId> \
               ParameterKey=DatabaseEndpoint,ParameterValue=<DatabaseEndpoint> \
               ParameterKey=DatabaseSecretArn,ParameterValue=<MasterUserSecretArn>
aws cloudformation wait stack-create-complete --stack-name controle-estoque-app --region us-east-2
```

**2. Enviar o jar.** O serviço tenta de novo a cada 15 segundos até encontrar o arquivo e sobe em seguida.
O link para os avaliadores é o output `SwaggerUrl`:

```bash
aws s3 cp target/ControleDeEstoque-0.0.1-SNAPSHOT.jar s3://<ArtifactBucketName>/app/controle-de-estoque.jar --region us-east-2
```

**3. Publicar uma nova versão:** envie o novo jar (passo 2) e reinicie o serviço:

```bash
aws ssm send-command --instance-ids <InstanceId> --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl restart estoque"]' --region us-east-2
```

**4. Remover (antes da stack do banco).** O bucket precisa estar vazio:

```bash
aws s3 rm s3://<ArtifactBucketName> --recursive --region us-east-2
aws cloudformation delete-stack --stack-name controle-estoque-app --region us-east-2
aws cloudformation wait stack-delete-complete --stack-name controle-estoque-app --region us-east-2
```

---

## 7. O que eu faria diferente em produção

1. **Idempotência**: header `Idempotency-Key` (ou unicidade `pedidoId + produto` na reserva)
   para que retries de rede não dupliquem reservas.
2. **Expiração de reservas** (TTL + job agendado ou fila com atraso) para devolver estoque retido.
3. **Ledger de movimentações** (tabela append-only de entradas, saídas, reservas e liberações) para
   auditoria e reconciliação; o saldo consolidado continua como projeção.
4. **Estratégia de lock por perfil de carga**: manter o otimista, com fallback para pessimista
   (`FOR UPDATE`) em produtos com alta disputa, e métricas de taxa de retry para decidir.
5. **Testes com Testcontainers + PostgreSQL** na mesma versão do RDS, e testes de carga (Gatling/k6)
   para o cenário de disputa.
6. **Segurança e credenciais**: a senha continua no **AWS Secrets Manager** com rotação (como no ambiente
   de avaliação), mas é lida pela própria aplicação em tempo de execução, sem passar por variáveis de
   ambiente (ex.: AWS Advanced JDBC Wrapper com o plugin do Secrets Manager), ou substituída por **IAM
   Database Authentication**; usuário de aplicação com privilégios mínimos (sem DDL; migrations com usuário
   separado no pipeline); autenticação OAuth2/JWT na API.
7. **Infra do RDS**, partindo do template de `infra/`: Multi-AZ e backups automáticos de 7+ dias com
   *point-in-time restore* (os dois exigem o plano pago e estão fora do ambiente de avaliação); instância em subnet
   **privada**, sem endpoint público, com a aplicação na mesma VPC (ex.: ECS Fargate); chave KMS gerenciada
   pelo cliente; logs do PostgreSQL no CloudWatch (log groups com KMS) e alarmes de conexões, CPU, memória e
   armazenamento; trilha do CloudTrail; regras do security group declaradas só como recursos separados
   (`AWS::EC2::SecurityGroupIngress`), sem regras embutidas, para o drift detection ficar limpo e nenhuma
   atualização depender de regras criadas por outra stack; classe de instância dimensionada por teste de carga, não
   *burstable*; e, se houver muitas instâncias da aplicação, RDS Proxy para pooling e failover mais rápido.
8. **Observabilidade com Datadog** (ou o equivalente nativo da AWS):
   - **APM e tracing distribuído** com o agente Java do Datadog (`dd-java-agent`): cada requisição vira um
     trace com os spans do controller, do serviço, das queries no PostgreSQL e das novas tentativas do lock
     otimista, para achar gargalos e erros sem reproduzir o problema.
   - **Logs estruturados em JSON** com `trace_id` e `span_id` injetados, ligando cada linha de log ao trace
     da requisição.
   - **Métricas de negócio** pelo Micrometer (`micrometer-registry-datadog` ou DogStatsD): reservas,
     bloqueios e liberações por minuto, taxa de 422 (estoque insuficiente), taxa de 409 (conflito de
     concorrência) e número de novas tentativas por operação, que é o sinal de disputa por um produto.
   - **Database Monitoring** do Datadog no RDS PostgreSQL: amostras de queries, planos de execução, locks e
     conexões. A integração com a AWS também traz as métricas do RDS, da EC2/ECS e do CloudFront.
   - **Monitores e SLOs**: latência p95/p99 e taxa de 5xx por endpoint, conflitos acima do normal, conexões
     do banco perto do limite e créditos de CPU acabando, com alertas no canal da equipe. Health checks pelo
     Spring Boot Actuator.
   - **Trade-off:** o Datadog unifica APM, logs e banco numa ferramenta, mas cobra por host e por volume. O
     caminho nativo é CloudWatch + X-Ray/ADOT + CloudWatch Database Insights, mais barato e já integrado à AWS.
9. **Eventos de domínio** (outbox pattern → SNS/SQS ou EventBridge) para notificar outros sistemas
   (pedidos, compras) sobre mudanças de saldo com garantia de entrega.
10. **API**: paginação, versionamento (`/api/v1`), rate limiting e ETag/`If-Match` usando a `version`.
11. **Policy as code na infraestrutura** (melhoria futura, não implementada): validar o template no CI
    com **cfn-lint** (sintaxe e boas práticas do CloudFormation) e **cfn-guard** (regras de segurança e
    compliance do AWS Guard Rules Registry e regras próprias da empresa), e ativar um **Guard Hook** do
    CloudFormation na conta para recusar stacks fora da política. Os desvios conscientes do ambiente de
    avaliação (endpoint público, Single-AZ, logs fora do CloudWatch) ficariam registrados no template como
    exceções explícitas e justificadas (`Metadata.guard.SuppressedRules`), em vez de silenciosos.
12. **Hospedagem da API**: contêiner no ECS Fargate atrás de um ALB, com várias tarefas em mais de uma AZ e
    auto scaling, em subnets privadas; o CloudFront alcançaria o ALB por *VPC origins*, sem IP público, com
    TLS também até a origem. Na frente, AWS WAF (regras gerenciadas e limite de requisições) e autenticação
    na API. Deploy por pipeline de CI/CD (build, testes, imagem no ECR, deploy gradual), no lugar do envio
    manual do jar.
13. **Escalabilidade da infraestrutura:**
    - **API sem estado**: escala na horizontal, com mais tarefas ECS ou instâncias num Auto Scaling Group
      atrás do ALB, em pelo menos duas AZs, escalando por CPU, latência ou requisições por alvo.
    - **Conexões com o banco**: cada instância da API tem seu pool (Hikari), então mais instâncias significam
      mais conexões. O RDS Proxy agrega as conexões e acelera o failover.
    - **Banco**: primeiro escala na vertical (classe maior, armazenamento gp3 com mais IOPS); depois com read
      replicas para as consultas de saldo e listagens. O Aurora PostgreSQL (inclusive Serverless v2) é a
      opção quando a carga varia muito.
    - **Produto muito disputado**: o limite real não é a infraestrutura, é a linha do produto. Com muita
      concorrência no mesmo produto, o lock otimista gera novas tentativas em excesso. As saídas são lock
      pessimista nesses produtos, serializar os comandos por produto numa fila (SQS FIFO com
      `MessageGroupId = sku`) ou dividir o saldo por armazém ou lote.
    - **Validação**: testes de carga (k6 ou Gatling) com o cenário de disputa, antes de definir limites de
      auto scaling e classe do banco.
14. **Frontend para os usuários do estoque.** A API já expõe um contrato OpenAPI, então qualquer frontend
    pode consumi-la com um cliente gerado automaticamente:
    - **ASP.NET Core** (Blazor ou Razor Pages): frontend em C#, uma das linguagens aceitas no case, com
      cliente tipado gerado do OpenAPI (NSwag ou Kiota). Também pode servir de BFF, concentrando
      autenticação e regras de tela.
    - **Microsoft Dynamics 365 / Power Apps**: para equipes que já usam o ecossistema Microsoft no back
      office, um *custom connector* criado a partir do OpenAPI permite montar telas e fluxos (Power
      Automate) sobre a API, sem frontend próprio.
    - **SPA em React ou Angular**, hospedada no S3 + CloudFront (o mesmo CloudFront da API).
    - Em todos os casos, a API precisaria de CORS configurado, autenticação OAuth2/OIDC (por exemplo,
      Microsoft Entra ID para Dynamics/ASP.NET) e versionamento do contrato.
15. **Logs e auditoria** (identificado na revisão, não implementado):
    - log estruturado (JSON, com ID de correlação) em cada operação: cadastro, entradas, saídas, reservas,
      bloqueios e liberações;
    - `data_criacao` em `produto` (migration `V3`) e uma tabela de movimentações com quem, quando, o quê e
      quanto, consultável em `GET /api/produtos/{sku}/movimentacoes` (concretiza o ledger do item 3);
    - logs centralizados: CloudWatch Agent ou agente do Datadog na EC2 enviando para um log group com
      retenção definida, logs de acesso do CloudFront no S3 e exportação dos logs do RDS;
    - access log do Tomcat (`server.tomcat.accesslog.enabled=true`) para registrar toda requisição.
16. **Catálogo de produtos** (o SKU único já foi implementado): edição de nome e marca com histórico; troca
    de SKU como operação administrativa controlada, porque o SKU é o identificador nas rotas (o código antigo
    ficaria como *alias* que redireciona para o novo, para não quebrar integrações);
    atributos de variação (cor, tamanho, layout); código de barras (EAN/GTIN) como identificador
    alternativo; e substituição dos SKUs provisórios `LEGADO-<id>` pelos códigos reais.
17. **Qualidade da API** (pontos da revisão ainda não implementados): `GET /api/produtos` paginado com
    filtro por nome; Actuator com health check do banco (substituindo o health check improvisado da EC2);
    GitHub Actions rodando os testes a cada push; teste de API do 409; envio em lote ao banco
    (`hibernate.jdbc.batch_size`) para pedidos com muitos itens. As mensagens em português, os erros de formato
    que indicam o campo, o JSON estrito, o limite de tamanho do corpo e o tratamento do erro 500 já foram
    implementados.
18. **Regras de negócio a decidir com a área**: limite máximo por operação (evitar erro de digitação de
    milhões de unidades); formato e existência do `pedidoId` no sistema de pedidos; no bloqueio, `motivo`
    obrigatório e `pedidoId` opcional (o PDF não exige pedido para bloquear, e uma avaria não tem pedido);
    reservas repetidas do mesmo pedido e produto (somar ou rejeitar); liberação e efetivação parciais;
    saída com tipo (venda, perda, ajuste); liberação e efetivação do pedido inteiro numa só chamada (hoje
    são feitas reserva por reserva).

---

## 8. Uso de IA

Ferramenta utilizada: **Claude Code** (Claude), integrado ao projeto.

### Principais prompts

1. *"Irei desenvolver um projeto em Java Spring Boot utilizando MVC. Para a classe Produto os
   atributos são: id (UUID), quantidadeTotal, quantidadeDisponivel, quantidadeReservada,
   quantidadeBloqueada, version (Long). Classe Reserva: id, produto, pedidoId, status (ATIVA,
   LIBERADA, EFETIVADA), dataCriacao. Classe Bloqueio: id, produto, motivo (opcional), pedidoId,
   status (ATIVO, LIBERADO), dataCriacao. [...] Crie um README [...]. Irei utilizar o RDS para a
   persistência dos dados."* (com o PDF do case anexado)
2. Escolha de engine (PostgreSQL) e do nível de detalhe das explicações.
3. *"No modelo atual o produto não possui nome, coloque um nome dele."* A IA adicionou `nome`
   obrigatório e não único, validado em três camadas (Bean Validation, domínio e CHECK no banco).
   Alterou a `V1` em vez de criar uma `V2` porque nenhuma base persistente tinha aplicado a migration.
4. *"Conecte-se à AWS [...] faça a infraestrutura através do MCP da AWS."* Pelo servidor MCP da AWS e
   pelas skills de RDS e CloudFormation, a IA levantou o plano da conta, as restrições (SCPs do plano Free),
   as versões disponíveis e os preços reais antes de escrever o template, e validou o deploy com um
   *change set*.
5. *"O que faz o cfn-guard"* e *"Não precisa implementar, mas anote como melhoria futura [...] e as
   limitações da infraestrutura."*
6. *"Não precisa de backup."* e *"Coloque a limitação no arquivo .md."*, depois que o primeiro deploy
   falhou pelo limite de retenção de backup do plano Free.
7. *"Quero que o RDS esteja disponível para eles [os avaliadores]."* A IA apresentou três caminhos
   (publicar a API, liberar os IPs dos avaliadores ou abrir o banco para a internet) e recomendou publicar
   a API, para não compartilhar a senha do banco nem expor a porta 5432.
8. *"Veja a menor instância de EC2 para os avaliadores poderem usar o Swagger"* e *"A utilização do GitHub
   Pages pode ajudar nisso?"* A IA consultou preços e elegibilidade reais: as `nano` são mais baratas, mas
   o plano Free não as aceita (e 512 MiB é pouco para Spring Boot). O GitHub Pages só hospedaria o Swagger
   estático, sem executar chamadas.
9. *"Nas ações futuras levante pontos de observabilidade com ferramentas como o Datadog, escalabilidade da
   infraestrutura, utilização de outra ferramenta para o frontend [...]; confira se as validações da
   aplicação fazem sentido, confira todo o passo a passo e compare com o PDF [...]"*. A revisão encontrou
   e corrigiu os pontos descritos em "Erros / limitações observados".
10. *"Confira possíveis validações futuras e me fale pontos para corrigir no código"*, *"Onde fica salvo os
    logs do cadastro de produtos?"* e *"Se eu cadastrar um produto como Teclado mecânico ABNT2 5000 vezes
    ele sempre irá ter Id diferente?"*. A IA testou cada ponto no ambiente publicado antes de responder
    (mensagens em inglês, reserva duplicada por reenvio, ausência de logs, cadastro duplicado com IDs
    diferentes). Decidi não implementar agora e registrar tudo nas limitações e na seção 7.
11. *"Faça os erros em português, e faça sempre um código 200 de preferência"*, esclarecido depois como
    *"ao invés de sucesso 201, faça retornar sucesso 200 com um corpo; não mexa em casos como 40X ou 50X"*.
    A IA avisou que responder 200 também nos erros quebraria clientes, monitoramento e health check, mediu
    o impacto da troca 201 → 200 (só a camada web e os testes) e traduziu todas as mensagens de erro.
12. *"Para o pedido preciso do produtoId [...] não faz sentido eu colocar mais de um produto porque ele não
    aceita no path"* e *"Faça a implementação desta rota"*. A IA criou `POST /api/pedidos/{pedidoId}/reservas`
    (tudo ou nada) e identificou um risco novo que a mudança trazia: deadlock entre transações que alteram
    vários produtos, prevenido com ordem fixa de atualização e coberto por teste de concorrência.
13. *"Organize melhor o Swagger"*. A IA reorganizou as operações pelo fluxo de uso (antes agrupadas por
    controller), adicionou o roteiro de teste e documentou automaticamente os erros de cada operação. Ao
    conferir o Swagger rodando, notou no log um aviso do springdoc causado pela própria definição e corrigiu
    antes de publicar.
14. *"Se eu cadastrar um produto como Teclado mecânico ABNT2 5000 vezes ele sempre irá ter Id diferente?
    [...] não faça muito sentido sendo que marcas variam"*, *"Posso criar um SKU para o projeto?"* e
    *"Implemente o SKU com texto livre e com letras, atualize e valide a aplicação, o banco e a
    infraestrutura, além do Swagger"*. A IA implementou o SKU em todas as camadas (domínio, migration `V3`
    com SKU provisório para os produtos existentes, API, Swagger e testes, inclusive de cadastros
    simultâneos), implantou na AWS e validou com drift detection, que encontrou dois desvios esperados,
    investigados e documentados.
15. *"Altere ao invés de usar o produto Id utilizar o sku"*, *"nas rotas do Swagger"* e *"anote o trade-off
    no arquivo .md"*. A IA explicou o trade-off antes de alterar (o SKU vira contrato público e precisa ser
    imutável), trocou o identificador do produto em todas as rotas, na reserva de pedido e nas respostas,
    removeu a busca separada por SKU e manteve o UUID só no banco, sem migration. Implantou na AWS e validou
    o fluxo completo pelo link público.
16. *"Veja se as validações estão todas okays [...] veja se o modelo de negócio está de acordo com o projeto
    sugerido no pdf [...] confira se todo o código está okay ou se é necessário otimizar"* e *"E ocorre quando
    coloco a quantidade 2.7?"*. Antes de mudar qualquer coisa, a IA mandou entradas de borda para a demo
    (PostgreSQL real) e mediu o comportamento: decimal arredondado, campo digitado errado ignorado, chave
    repetida, caractere nulo virando 409, quebra de linha aceita. Corrigiu cada caso com teste, além de dois
    defeitos de código (data com precisão maior que a gravada e ordem das listagens não determinística) e de
    uma otimização (uma consulta só na reserva de pedido). Depois repetiu a mesma bateria na demo.
17. *"Faça uma atualização final no arquivo .md e prepare um pacote final para ser enviado para os
    avaliadores"*. A IA acrescentou ao README um guia que liga cada requisito do PDF ao código e à seção
    correspondente, e verificou o repositório num clone limpo, como o avaliador fará.

### Onde a IA mais ajudou
- Gerar a estrutura completa (domínio, serviço, controllers, migration, testes) de forma consistente
  com o Spring Boot 4 (novos starters modulares, pacotes de teste reorganizados).
- Desenhar os testes de concorrência e **verificar que eles realmente exercitam o retry**
  (117 conflitos de versão resolvidos numa execução), em vez de confiar apenas no "passou".
- Sondar o ambiente real com entradas de borda (decimal, campo com erro de digitação, caractere nulo) antes
  de corrigir: isso revelou falhas que 85 testes verdes não pegavam, e a mesma bateria confirmou a correção.

### Sugestões da IA que modifiquei ou não utilizei
- **Backup de 7 dias → sem backup.** A skill de RDS aplicou 7 dias de retenção como padrão de produção. Decidi
  ficar sem backup: o ambiente é temporário, os dados são descartáveis e o plano Free rejeitou os 7 dias.
  A consequência (sem *point-in-time restore* nem snapshot final) está nas limitações.
- **cfn-guard → não implementado agora.** A IA ofereceu rodar a checagem de políticas no template. Para o
  case, o ganho seria baixo (só confirmaria desvios já conhecidos), então ficou registrado como melhoria
  futura na seção 7.
- **201 Created → 200 nos cadastros.** A IA tinha seguido a convenção REST (201 + `Location`). Preferi que
  todo sucesso responda 200 com o recurso no corpo, mantendo os códigos 4xx e 5xx nos erros, como a IA
  recomendou ao desaconselhar 200 também para erros.
- **cfn-lint → não utilizado.** Preferi não instalar uma ferramenta extra só para o lint; a validação ficou
  no `ValidateTemplate` e na validação pré-deploy do CloudFormation (limitação documentada).
- **UUID nas rotas + busca por SKU → SKU nas rotas.** Ao implementar o SKU, a IA manteve o UUID como
  identificador das rotas e criou uma busca separada (`/api/produtos/busca?sku=`), priorizando a
  estabilidade do UUID. Preferi identificar o produto pelo SKU em todas as rotas, por ser o código que o
  negócio usa. O custo da escolha (SKU imutável, exposto em URLs) está na seção 3.

### Ajustes feitos pela IA e pontos de atenção
- O modelo original de `Reserva`/`Bloqueio` **não tinha `quantidade`**; a IA apontou a lacuna e o
  campo foi adicionado.
- Para o retry, a IA descartou `@Retryable` + `@Transactional` em favor de um executor explícito com
  `TransactionTemplate`, por causa do risco de ordem de proxies.
- A ausência de Docker no ambiente levou a usar H2 em vez de Testcontainers; a limitação está
  documentada.
- A skill de RDS da AWS recomenda padrões de produção (Multi-AZ, endpoint privado, chave KMS própria, logs
  no CloudWatch). Para um ambiente temporário no plano Free, a IA apresentou cada desvio com o custo e a
  justificativa antes de implantar; o Multi-AZ nem é permitido nesse plano.
- A skill de CloudFront da AWS recomenda *VPC origins* (EC2 em subnet privada) como padrão mais seguro.
  A IA optou pelo *security group* restrito ao prefix list do CloudFront, porque a VPC padrão só tem subnets
  públicas e uma subnet privada exigiria NAT Gateway ou *VPC endpoints*; o desvio está documentado.
- Antes do deploy, a IA antecipou que o Swagger atrás do CloudFront geraria links para o endereço interno
  da EC2 (bloqueado pelo security group). A correção foi um contrato OpenAPI com servidor relativo e o
  repasse do `Host` e de `X-Forwarded-Proto: https` pelo CloudFront, verificados no teste de ponta a ponta.
- Na troca do UUID pelo SKU, a IA antecipou que mostrar o `sku` nas respostas de reserva e bloqueio daria
  erro 500: o produto é carregado sob demanda (*lazy*) e a resposta é montada fora da transação. A correção
  foi carregar o produto junto nas consultas (`@EntityGraph`), com um teste que lê o SKU fora da transação.
- Na validação de caracteres de controle, a IA descartou `@Pattern`: a regex que aceita espaços nas pontas
  fica ambígua e pode levar tempo exponencial num corpo grande (ReDoS). Usou um validador linear, com a mesma
  regra do domínio. Pelo mesmo motivo de robustez, limitou o corpo a 100 KB, já que a API da demo é pública e
  roda com 400 MB de memória.

### Erros / limitações observados
- O modelo de IA inicialmente não havia coberto erros de modelagem para atributos na entidade produtos para
  futuros testes feitos por humanos; o campo "Nome" foi um deles.
- O servidor MCP da AWS falhou ao conectar com a mensagem genérica *Invalid request parameters*. A causa
  real era a sessão do `aws login` expirada; o diagnóstico só veio ao testar o proxy do MCP fora da sessão.
- A IA configurou retenção de backup de 7 dias, padrão da skill de RDS, sem saber que o plano Free limita
  esse valor (a documentação consultada não citava o limite). O primeiro deploy falhou com *"backup
  retention period exceeds the maximum available to free tier customers"* e o CloudFormation reverteu tudo
  sozinho, sem custo. Como o ambiente é temporário, a decisão foi ficar sem backup.
- Na revisão final contra o PDF, apareceram dois problemas no código gerado pela IA, que tinham passado em
  todos os testes da época: (1) uma entrada que estourasse o limite de `Integer` respondia **500**, em vez
  de uma regra de negócio (422); (2) `pedidoId` e `motivo` eram validados só na API, enquanto `nome` tinha
  validação na API, no domínio e no banco. Os dois foram corrigidos com testes, e a regra do banco entrou
  como a migration `V2`, porque a `V1` já estava aplicada no RDS. Lição: testes verdes não substituem uma
  revisão crítica dos casos de borda.
- A seção de uso de IA misturava ajustes feitos pela própria IA com decisões minhas; foi reorganizada para
  responder exatamente ao que o PDF pede.
- O exemplo de `curl` que a IA escreveu no README usa texto com acento e falha no Git Bash do Windows (o
  terminal não envia UTF-8). A IA só percebeu ao testar no ambiente real; o README agora traz o aviso.
- **A IA desenhou a reserva apenas por produto** (`POST /api/produtos/{id}/reservas`), seguindo o enunciado
  ao pé da letra, e não previu que um pedido real tem vários itens. Um pedido com três produtos exigia três
  chamadas independentes, sem atomicidade: se a terceira falhasse, o pedido ficava reservado pela metade e
  o cliente teria de desfazer as outras. A falha foi apontada por mim, não pela IA, depois de todas as
  revisões anteriores. A correção foi a rota `POST /api/pedidos/{pedidoId}/reservas`, tudo ou nada.
- **A IA não modelou uma chave de negócio para o produto.** O modelo identificava o produto só pelo UUID, e
  nada impedia cadastrar o mesmo item várias vezes: "Teclado mecânico ABNT2" cadastrado duas vezes virava
  dois produtos, com o saldo dividido entre eles (uma reserva podia falhar por estoque insuficiente em um
  registro enquanto o outro tinha unidades sobrando). A IA chegou a documentar o SKU como algo "para um
  módulo de catálogo", tratando como detalhe de cadastro o que na verdade era um requisito de consistência
  do estoque. A falha foi apontada por mim ("marcas variam"), não pela IA. A correção foi o SKU único em
  todas as camadas, com 409 para SKU repetido e a migration `V3`.
- **A detecção de drift mostrou dois efeitos colaterais do desenho da infraestrutura feito pela IA**: a regra
  que a stack da API acrescenta ao security group do banco aparece como divergência, e o `rds.force_ssl=1`
  não ficou registrado como parâmetro explícito porque é o valor padrão. Nenhum dos dois afeta a segurança
  (o TLS obrigatório foi testado), mas ambos só apareceram quando a validação foi além de "os testes passaram".
- Num teste novo, a IA usou `List.getFirst()`, que só existe a partir do Java 21, num projeto com alvo Java 17.
  A máquina de desenvolvimento roda Java 21, mas o compilador respeita o alvo e recusou o código. O erro foi
  pego na primeira compilação e trocado por `get(0)`: um lembrete de que o alvo do projeto, e não a JDK
  instalada, define a API disponível. Na revisão seguinte, a IA repetiu o mesmo tipo de erro com
  `List.reversed()` (também do Java 21) e só percebeu ao reler o próprio teste, antes de compilar.
- **A IA confiou nos padrões tolerantes do Jackson.** Em todas as revisões anteriores, ninguém testou o que a
  API fazia com um JSON "quase certo": `"quantidade": 2.7` dava baixa de 2 unidades com resposta 200, um erro
  de digitação em `quantidadeInicial` criava o produto com 0 unidades sem aviso, e numa chave repetida valia o
  último valor. Os 85 testes passavam porque só cobriam entradas bem formadas ou claramente erradas. A falha
  só apareceu quando a revisão mandou entradas de borda ao ambiente real; a correção foi o contrato JSON
  estrito (seção 3).
- **A data devolvida na criação não era a gravada.** O código gerado pela IA usava a precisão de
  nanossegundos do Java, mas o banco guarda microssegundos: a resposta da criação mostrava
  `…15.263094069` e as consultas seguintes, `…15.263094`. Nenhum teste comparava os dois valores; a
  diferença apareceu lendo as respostas da demo.

---

## 9. Visão de engenharia: 5 aspectos mais importantes

1. **Consistência transacional e invariantes explícitas.** O valor do sistema é o saldo estar
   certo. Invariantes no domínio, atomicidade por transação e CHECK constraints no banco formam
   três camadas de proteção.
2. **Controle de concorrência.** Estoque é um recurso disputado. Sem lock (otimista ou pessimista),
   duas reservas simultâneas vendem a mesma unidade. A estratégia precisa ser escolhida, medida e
   testada sob carga real.
3. **Testes em múltiplos níveis, incluindo concorrência.** Testes unitários de domínio dão
   feedback rápido; integração e concorrência provam o que de fato importa em produção.
4. **Idempotência e rastreabilidade.** Em sistemas distribuídos, requisições se repetem. Reservas
   identificáveis, status explícitos e um futuro ledger permitem reconciliar e auditar.
5. **Observabilidade e evolução segura.** Métricas de conflitos e erros de negócio, migrations
   versionadas e um contrato de API estável (DTOs, RFC 9457) permitem evoluir sem quebrar
   consumidores nem corromper dados.
