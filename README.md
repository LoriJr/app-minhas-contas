# Minhas Contas (Fatura)

Gerenciador de contas mensais em português: React 19 + Vite, Java 21 + Spring Boot 3.5 + Spring Security e PostgreSQL 17 com Flyway. Interface responsiva web, com manifesto para adicionar à tela inicial do celular. Requer conexão à internet.

O repositório inclui **CI/CD completo** (GitHub Actions → Docker Hub → GKE) com recuperação de desastre *self-healing*: um push na `main` reconstrói imagem, aplica manifests, recria Secrets e IP estático automaticamente.

## Sumário

**Produto**
- [Visão geral](#visão-geral)
- [Executar localmente](#executar-localmente-docker-compose)
- [Contas recorrentes](#contas-recorrentes)
- [Atualização de instalações anteriores](#atualização-de-instalações-anteriores)
- [Lembretes](#lembretes)
- [Segurança da aplicação](#segurança-da-aplicação)
- [Testes](#testes)

**Deploy no GKE (CI/CD)**
- [Visão geral do pipeline](#visão-geral-do-pipeline)
- [Checklist de nomes (LEIA PRIMEIRO)](#checklist-de-nomes-leia-primeiro)
- [Secrets no GitHub](#secrets-no-github)
- [Setup inicial do zero](#setup-inicial-do-zero-primeira-vez-15-min)
- [Recuperação de desastre](#recuperação-de-desastre-dr)
- [Operação e debug](#operação-e-debug)
- [Variáveis de ambiente](#variáveis-de-ambiente)
- [Cleanup](#cleanup)
- [Troubleshooting](#troubleshooting-rápido)

---

## Visão geral

Aplicação web para acompanhar **contas mensais recorrentes** (energia, internet, aluguel, assinaturas etc.). Cada conta vence todo mês no dia escolhido; você registra o pagamento mês a mês, e o painel mostra o que está próximo do vencimento ou vencido. Envio de lembretes por e-mail é opcional (SMTP).

Stack:
- **Frontend:** React 19 + Vite, manifest PWA (instalável na tela inicial do celular).
- **Backend:** Java 21 + Spring Boot 3.5 + Spring Security (sessão, BCrypt, CSRF).
- **Banco:** PostgreSQL 17 com migrações Flyway.
- **Empacotamento:** imagem Docker multi-stage (frontend build → backend build → runtime enxuto).
- **Deploy:** Kubernetes (GKE Standard) com Ingress GCE + ManagedCertificate opcional.

## Executar localmente (Docker Compose)

Instale Docker e Docker Compose. Copie `.env.example` para `.env`, defina uma senha exclusiva para o banco e execute:

```sh
docker compose up --build
```

Abra http://localhost:8080. Crie um usuário com senha de 12 a 72 bytes UTF-8 (no mínimo 12 caracteres). Não existem credenciais padrão. Frontend e API são servidos pela mesma origem.

Para desenvolvimento: configure PostgreSQL, `DATABASE_PASSWORD` e `COOKIE_SECURE=false`; execute `mvn spring-boot:run` em `backend`. No frontend execute `npm ci` e `npm run dev`. O Vite encaminha `/api` para `localhost:8080`.

## Contas recorrentes

1. Clique em **Nova conta mensal**.
2. Informe nome, categoria, valor, **dia do vencimento (1 a 31)**, antecedência do lembrete e empresa/beneficiário.
3. Salve. A conta passa a aparecer todos os meses a partir do mês corrente, sem data final e sem importação manual.
4. Navegue pelos meses para consultar as ocorrências. Registre cada mês como pago separadamente.
5. Em **Editar detalhes**, ajuste valores e demais informações. A alteração vale para o mês escolhido e os próximos meses em aberto; os meses anteriores e outros meses já pagos são preservados.
6. Em **Excluir conta mensal**, confirme **Excluir recorrência e histórico**. A exclusão apaga a conta de todos os meses e todos os seus registros de pagamento. Não pode ser desfeita.

Exemplo: uma conta com dia 10 aparece em 10/11, 10/12, 10/01 etc. Pagar novembro não paga dezembro. Para dia 31, fevereiro usa o último dia (28 ou 29), e março volta ao dia 31. O valor se repete como padrão, podendo ser ajustado quando houver variação. Não há parcelas ou limite de meses.

Os meses são criados conforme a consulta e a execução dos lembretes, sem inserir uma quantidade infinita de registros no banco. Ao retomar o uso após um período sem acesso, os meses decorridos são gerados e as contas não pagas continuam visíveis nos lembretes. A geração é idempotente, protegida por bloqueio por usuário e índice único por recorrência/mês.

## Atualização de instalações anteriores

Faça backup antes de atualizar. Execute novamente `docker compose up --build`; o Flyway aplica V3 automaticamente. Não modifique V1/V2 e não apague o volume do banco.

A migração transforma as contas existentes em recorrências. Cópias criadas pela antiga importação mensal são reunidas pela sua origem, preservando valores, vencimentos e pagamentos de cada mês. Cadastros manuais independentes continuam independentes, mesmo com nomes iguais. Se duas ocorrências da mesma origem tiverem sido movidas para o mesmo mês, ambas são preservadas como recorrências independentes; revise eventuais duplicatas depois da atualização. O dia de vencimento padrão vem da conta de origem, evitando que dia 31 vire permanentemente dia 28.

Os campos de forma de pagamento e código original foram retirados da interface, da API e do banco na V3. A geração de QR Code e as dependências correspondentes foram removidas. Não há integração com APIs de concessionárias, emissão de boletos, Pix ou execução de pagamentos. Empresa/beneficiário continua sendo um campo descritivo.

A demonstração usa dados fictícios no navegador e migra seu formato anterior automaticamente; não envia e-mails nem faz pagamentos.

## Lembretes

O painel mostra contas próximas do vencimento e vencidas. Para e-mails, configure `MAIL_ENABLED=true`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` e `MAIL_FROM` no servidor. STARTTLS está habilitado.

O agendador roda a cada 15 minutos, no fuso `America/Sao_Paulo`. Desde a antecedência escolhida até o registro do pagamento, tenta enviar um aviso por data para cada ocorrência mensal. Também prepara o próximo mês para lembrar, por exemplo, de uma conta que vence no dia 1. Falhas de envio são tentadas novamente no próximo ciclo. As credenciais do SMTP ficam no servidor. Não há SMS, push nem alarme sonoro em segundo plano nesta versão.

O controle de envio e o registro de pagamento são separados por mês. O bloqueio por usuário evita alterações concorrentes durante a operação, mas uma queda após o SMTP aceitar uma mensagem e antes do commit pode duplicar o aviso; para garantias adicionais, adote outbox/fila e idempotência no provedor. O envio depende do servidor ativo e do SMTP disponível.

## Segurança da aplicação

Spring Security com sessão, senha BCrypt, proteção CSRF, cookie HttpOnly e acesso aos dados limitado ao proprietário. Para publicar, use HTTPS e `COOKIE_SECURE=true`, mantenha o banco privado e configure backup. Recuperação/verificação de e-mail, limitação de tentativas, auditoria e fluxos de privacidade/exclusão da conta de usuário ainda precisam ser desenvolvidos antes de abrir o serviço ao público.

## Testes

```sh
cd frontend
npm ci
npm test
npm run build
```

```sh
cd backend
mvn test
```

Os testes Java incluem segurança da API, recorrência, independência dos pagamentos, alterações de valor/vencimento, preservação do histórico e exclusão. Os testes PostgreSQL são opcionais e exigem **um banco exclusivo e descartável de testes**, informado por `TEST_DATABASE_URL`, `TEST_DATABASE_USER` e `TEST_DATABASE_PASSWORD`. Com essas variáveis, `mvn test` também verifica a migração de dados legados, índices, persistência, isolamento entre usuários e lembretes. Os dados desses testes são revertidos; a migração usa e remove um schema temporário exclusivo.

Validação em 06/11/2026: compilação do frontend, 6 testes Node e 19 testes Java (incluindo 6 testes com PostgreSQL 17) concluídos com sucesso. Navegação real, SMTP real e hospedagem não foram verificados.

---

# Deploy no GKE (CI/CD)

> Guia de infra: primeira configuração, operação diária e recuperação de
> desastre. Leia o **[Checklist de nomes](#checklist-de-nomes-leia-primeiro)**
> antes de criar qualquer recurso no GCP — os nomes precisam bater com o que
> está codificado no workflow, senão o deploy quebra silenciosamente.

## Visão geral do pipeline

- **Deploy inicial (primeira vez):** siga [Setup inicial do zero](#setup-inicial-do-zero-primeira-vez-15-min).
- **Rotina:** `git push origin main` → CI/CD builda a imagem, publica no Docker Hub, aplica manifests no GKE. Zero intervenção manual.
- **Recuperação de desastre** (cluster perdido, secrets apagados, IP liberado): o workflow é *self-healing* — recria Secret k8s e IP estático automaticamente. Veja [Recuperação de desastre](#recuperação-de-desastre-dr).

```
git push na main
   │
   ▼
┌────────────────────────────────────────────────────────────┐
│ Job 1: build                                               │
│  • lê versão de backend/pom.xml                            │
│  • builda imagem Docker (multi-stage: frontend + backend)  │
│  • publica loujrdocker/minhas-contas:<versão> no Hub       │
└────────────────────────────────────────────────────────────┘
   │
   ▼
┌────────────────────────────────────────────────────────────┐
│ Job 2: deploy                                              │
│  • autentica no GCP via GCP_SA_KEY                         │
│  • pega credenciais do cluster (GKE_CLUSTER + GKE_ZONE)    │
│  • cria/atualiza Secret k8s 'app-secrets' (idempotente)    │
│  • cria IP estático 'minhas-contas-ip' se não existir      │
│  • aplica 6 manifests em ordem                             │
│  • kubectl set image deployment/minhas-contas → :<versão>  │
│  • aguarda rollout + lista pods/ingress                    │
└────────────────────────────────────────────────────────────┘
```

## Checklist de nomes (LEIA PRIMEIRO)

O workflow e os manifests têm nomes hardcoded. Antes de criar qualquer coisa no GCP, confirme os nomes que você vai usar.

### 🔴 Nomes FIXOS (hardcoded — mudar exige editar arquivo + commit)

| Recurso | Nome obrigatório | Onde está hardcoded |
|---|---|---|
| Usuário Docker Hub | `loujrdocker` | `.github/workflows/minhas-contas-deployment.yml` (linhas 60, 102, 103, 197) |
| Repo Docker Hub | `minhas-contas` | idem |
| Namespace Kubernetes | `minhas-contas` | todos os `sk8/*.yaml` + workflow |
| Secret k8s (DB/SMTP) | `app-secrets` | workflow linha 149, `sk8/postgres.yaml`, `sk8/app-deployment.yaml` |
| Deployment/Service da app | `minhas-contas` | `sk8/app-*.yaml`, `sk8/ingress.yaml`, workflow |
| Deployment/Service do Postgres | `postgres` | `sk8/postgres.yaml`, `sk8/configmap.yaml` (DATABASE_URL) |
| PVC do Postgres | `postgres-pvc` | `sk8/postgres.yaml` |
| Banco Postgres (DB name) | `fatura` | `sk8/postgres.yaml` linha 45, `configmap.yaml` linha 8 |
| Usuário Postgres | `fatura` | `sk8/postgres.yaml` linha 47, `configmap.yaml` linha 9 |
| **IP estático global GCP** | `minhas-contas-ip` | workflow linha 161 **E** `sk8/ingress.yaml` linha 19 |
| Ingress GKE | `minhas-contas-ingress` | `sk8/ingress.yaml` |
| ManagedCertificate | `minhas-contas-cert` | `sk8/ingress.yaml` |
| ConfigMap | `app-config` | `sk8/configmap.yaml`, `sk8/app-deployment.yaml` |

> ⚠️ **IP estático**: o nome aparece em **2 lugares** (workflow + annotation do Ingress). Mudar só em um = Ingress fica sem IP e reconcilia infinitamente em silêncio.

### 🟡 Nomes FLEXÍVEIS (lidos de Secret → você escolhe, só precisa bater com o Secret)

| Recurso GCP | Secret que define o nome | Observações |
|---|---|---|
| Projeto GCP | `GCP_PROJECT_ID` | seu project ID real |
| Cluster GKE | `GKE_CLUSTER` | pode ser qualquer nome (ex: `meu-cluster-v2`) |
| Location do cluster | `GKE_ZONE` ⚠️ | aceita **zona** (`us-central1-a`) **OU** **região** (`us-central1`) — o nome do Secret é confuso, mas a action `get-gke-credentials` aceita ambos. **Deve bater** com o modo como o cluster foi criado (`--zone` vs `--region`). |
| Service Account | implícito em `GCP_SA_KEY` | o e-mail da SA está embutido no JSON que você cola no Secret |

> 💡 Este guia assume os nomes default (`minhas-contas-cluster`, `us-central1-a`, `gke-deployer`, `minhas-contas-proj`). Você pode trocar — só mantenha a coerência entre o recurso real e o valor do Secret no GitHub.

### 🟢 Pegadinhas críticas

1. **Cluster ZONAL vs REGIONAL.** `gcloud container clusters create ... --zone=us-central1-a` cria ZONAL; `--region=us-central1` cria REGIONAL. O valor de `GKE_ZONE` **tem que bater** com o modo escolhido, senão `get-gke-credentials` falha com "cluster not found".

   ```bash
   # Verificar o modo depois de criado:
   gcloud container clusters describe NOME --region=us-central1   # se retornar, é REGIONAL
   gcloud container clusters describe NOME --zone=us-central1-a   # se retornar, é ZONAL
   ```

2. **IP estático é GLOBAL.** Sempre `gcloud compute addresses create ... --global`. A anotação `kubernetes.io/ingress.global-static-ip-name` **só** resolve IPs globais. Se criar sem `--global`, o Ingress nunca acha.

3. **Nome da SA embutido no JSON.** A chave JSON contém o e-mail `gke-deployer@SEU_PROJECT.iam.gserviceaccount.com`. Se você renomeou a SA para `gke-deployer-v2` mas colou a chave antiga no Secret `GCP_SA_KEY`, o workflow falha na autenticação. A chave é emitida **no momento** `gcloud iam service-accounts keys create` e o e-mail fica travado dentro dela.

4. **`sk8/secret.yaml` NÃO está neste repo** (está no `.gitignore`). Ele é um placeholder local, e **não** precisa existir pra você rodar o deploy: o workflow cria o Secret `app-secrets` direto com `kubectl create secret` + `apply` a partir dos GitHub Secrets `DATABASE_PASSWORD` e `SMTP_PASSWORD`. O `kustomization.yaml` **não** referencia `secret.yaml` (por isso `kubectl apply -k sk8/` funciona limpo).

   Se você quiser um `sk8/secret.yaml` local para testar via `kubectl apply -f`, crie do zero com este conteúdo mínimo:

   ```yaml
   apiVersion: v1
   kind: Secret
   metadata:
     name: app-secrets
     namespace: minhas-contas
   type: Opaque
   stringData:
     DATABASE_PASSWORD: "SUA-SENHA-AQUI"
     SMTP_PASSWORD: ""
   ```

   Mas prefira sempre o caminho do CI/CD: mude o Secret no GitHub → push → o workflow faz upsert. Nunca commitar senha real mesmo com `secret.yaml` gitignored (histórico do git pode revelar).

## Secrets no GitHub

Vá em **Settings → Secrets and variables → Actions → New repository secret** e crie exatamente estes 8 (nomes exatos, case-sensitive):

| # | Nome do Secret | O que é | Onde obter |
|---|---|---|---|
| 1 | `DOCKER_USERNAME` | user do Docker Hub | seu user (ex: `loujrdocker`) |
| 2 | `DOCKER_ACCESS_TOKEN` | PAT com escopo Read & Write | Docker Hub → Account Settings → Personal access tokens → Generate |
| 3 | `GCP_SA_KEY` | JSON completo da chave da SA | gerada por `gcloud iam service-accounts keys create` (runbook abaixo) |
| 4 | `GCP_PROJECT_ID` | ID do projeto GCP | `gcloud config get-value project` |
| 5 | `GKE_CLUSTER` | nome do cluster | ex: `minhas-contas-cluster` |
| 6 | `GKE_ZONE` | location do cluster | ex: `us-central1-a` (zonas) **ou** `us-central1` (regional) |
| 7 | `DATABASE_PASSWORD` | senha do Postgres (DB `fatura`) | você escolhe. O workflow injeta esse valor no Secret k8s `app-secrets`. |
| 8 | `SMTP_PASSWORD` | senha SMTP **ou vazio** | pode deixar vazio (`""`) enquanto `MAIL_ENABLED=false` |

> ⚠️ O `DATABASE_PASSWORD` no GitHub é a **única fonte da verdade** da senha do Postgres. Se você mudar a senha direto no cluster com `kubectl edit secret`, o próximo deploy pisa com o valor do GitHub. Sempre mude no GitHub.

## Setup inicial do zero (primeira vez, ~15 min)

Substitua `SEU_PROJECT_ID` e os demais placeholders. Este guia usa os nomes default (`minhas-contas-cluster`, `minhas-contas-ip`, `gke-deployer`) — se escolher outros, atualize os Secrets correspondentes.

```bash
# 0. Contexto gcloud
gcloud config set project SEU_PROJECT_ID
gcloud config set compute/region us-central1

# 1. APIs (só na primeira vez no projeto)
gcloud services enable container.googleapis.com compute.googleapis.com iam.googleapis.com

# 2. Cluster REGIONAL (custo ~$0.04/h ≈ $29/mês com 1 nó e2-small)
gcloud container clusters create minhas-contas-cluster \
  --region=us-central1 \
  --num-nodes=1 \
  --machine-type=e2-small \
  --enable-ip-alias \
  --release-channel=regular

# 3. Service Account do CI/CD
gcloud iam service-accounts create gke-deployer \
  --display-name="GKE Deployer (CI/CD)"

# 4. Dar permissão mínima na SA (edita workload, não o cluster em si)
gcloud container clusters add-iam-policy-binding minhas-contas-cluster \
  --region=us-central1 \
  --member="serviceAccount:gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/container.developer"

# 5. Gerar chave JSON da SA → vira o Secret GCP_SA_KEY no GitHub
gcloud iam service-accounts keys create gke-deployer-key.json \
  --iam-account=gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com

# (segurança) apague o JSON local depois de colar no GitHub
```

### Configurar os 8 secrets no GitHub

GitHub → seu repo → **Settings → Secrets and variables → Actions → New repository secret**. Preencha um a um, com os nomes exatos da tabela acima.

### Primeiro deploy

```bash
git push origin main
```

Acompanhe em **https://github.com/SEU_USER/SEU_REPO/actions**. O primeiro run demora ~10 min (build multi-stage + cold start Spring Boot).

### Descobrir o IP público

Depois do deploy:

```bash
gcloud compute addresses describe minhas-contas-ip --global --format='value(address)'
# ou:
kubectl get ingress -n minhas-contas
```

Aponte o DNS do seu domínio (entrada **A**) para esse IP. Depois edite `sk8/ingress.yaml`:
- linha 9: troque `minhas-contas.seudominio.com.br` pelo seu domínio real
- linhas 25-26: descomente `managed-certificates` e `allow-http: false`
- em `sk8/configmap.yaml` linha 14: troque `COOKIE_SECURE: "false"` para `"true"`

Faça push → o ManagedCertificate do GKE emite o Let's Encrypt automaticamente (leva ~5 min).

## Recuperação de desastre (DR)

O workflow foi feito **self-healing**: apagar cluster, namespace, Secrets ou IP não exige intervenção manual — só reconstruir o que **não** pode ser feito pelo runner (chicken-and-egg: o runner precisa de cluster E de secrets configurados pra existir).

### O que o CI faz sozinho vs o que você precisa fazer

| Perdeu... | Passos manuais | Tempo total |
|---|---|---|
| Um pod | **Nenhum** — k8s reinicia sozinho | ~1 min |
| O namespace `minhas-contas` | **Nenhum** — CI re-aplica `namespace.yaml` | ~6 min (próximo push) |
| O Secret k8s `app-secrets` | **Nenhum** — step *Ensure app-secrets* faz upsert | ~6 min |
| O IP estático `minhas-contas-ip` | **Nenhum** — step *Ensure global static IP* recria | ~6 min |
| Os manifests k8s (deploy/svc/ingress) | **Nenhum** — CI re-aplica | ~6 min |
| O **cluster** GKE inteiro | `gcloud container clusters create` + dar role na SA + **talvez** atualizar `GKE_CLUSTER`/`GKE_ZONE` se nomes mudaram | ~10 min + 6 min CI |
| A chave da SA (vazada / expirada) | Revogar no GCP, gerar nova, atualizar Secret `GCP_SA_KEY` | ~5 min |
| O **projeto** GCP inteiro | Refazer setup completo (runbook acima) | ~15 min + 6 min CI |
| A conta Docker Hub | Trocar `loujrdocker` em 4 lugares do workflow + recriar PAT + atualizar `DOCKER_USERNAME`/`DOCKER_ACCESS_TOKEN` | ~20 min + 6 min CI |

### Runbook DR: cluster sumiu, projeto intacto

```bash
# Recria cluster com mesmo nome, região e SA existente:
gcloud container clusters create minhas-contas-cluster \
  --region=us-central1 \
  --num-nodes=1 --machine-type=e2-small \
  --enable-ip-alias --release-channel=regular

# Reconecta a SA (role é por cluster, precisa re-bindar):
gcloud container clusters add-iam-policy-binding minhas-contas-cluster \
  --region=us-central1 \
  --member="serviceAccount:gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/container.developer"

# Se os nomes do cluster / location não mudaram, os secrets GitHub já batem.
# Agora é só disparar o CI/CD:
git push origin main
# (ou: aba Actions → "Run workflow")
```

O CI vai: criar `app-secrets`, criar/reaproveitar `minhas-contas-ip`, aplicar manifests, fixar a imagem e verificar pods.

> ⚠️ **Atenção — backup do banco.** O PVC `postgres-pvc` referencia um PersistentVolume que **morre junto com o cluster** (PV em disco PD local). DR de cluster sem backup externo = perda dos dados. Soluções:
> - migração para **Cloud SQL** (Postgres gerenciado, backup automático)
> - snapshot manual dos PDs (`gcloud compute disks snapshot ...`) em cron
> - `pg_dump` periódico pro GCS (pod `backup-cronjob` — a implementar)

### Runbook DR: chave JSON da SA vazada

```bash
# 1. Revogar a chave vazada (pegue KEY_ID do e-mail de alerta ou do console)
gcloud iam service-accounts keys revoke KEY_ID \
  --iam-account=gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com

# 2. Gerar nova chave
gcloud iam service-accounts keys create gke-deployer-key.json \
  --iam-account=gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com

# 3. GitHub → Secrets → atualizar GCP_SA_KEY com o novo JSON
# 4. github.com → Actions → "Run workflow" → Deploy para confirmar
```

## Operação e debug

```bash
# Configurar kubectl local (se ainda não configurou)
gcloud container clusters get-credentials minhas-contas-cluster --region=us-central1

# Pods rodando?
kubectl get pods -n minhas-contas

# Logs da aplicação
kubectl logs -f deployment/minhas-contas -n minhas-contas

# Logs do banco
kubectl logs -f deployment/postgres -n minhas-contas

# Descrever pod (debug de crashes / ImagePullBackOff)
kubectl describe pod -n minhas-contas -l app=minhas-contas

# Status do Ingress (IP, endpoints, erros de certificado)
kubectl describe ingress minhas-contas-ingress -n minhas-contas

# Reverter último deploy (rolling back)
kubectl rollout undo deployment/minhas-contas -n minhas-contas

# Forçar re-execução do CI/CD sem push novo
# (GitHub → Actions → "Docker Image Minhas-Contas" → Run workflow)
```

## Variáveis de Ambiente

| Variável | Origem | Exemplo / default |
|---|---|---|
| `DATABASE_URL` | ConfigMap | `jdbc:postgresql://postgres:5432/fatura` |
| `DATABASE_USER` | ConfigMap | `fatura` |
| `DATABASE_PASSWORD` | Secret `app-secrets` | (vem do GitHub Secret `DATABASE_PASSWORD`) |
| `COOKIE_SECURE` | ConfigMap | `"false"` em HTTP, `"true"` em HTTPS+ManagedCertificate |
| `MAIL_ENABLED` | ConfigMap | `"false"` (true quando SMTP estiver configurado) |
| `SMTP_HOST` | ConfigMap | `smtp.gmail.com` |
| `SMTP_PORT` | ConfigMap | `587` |
| `SMTP_USER` | ConfigMap | seu-email@gmail.com |
| `SMTP_PASSWORD` | Secret `app-secrets` | (vem do GitHub Secret `SMTP_PASSWORD`) |
| `SMTP_AUTH` | ConfigMap | `"true"` |
| `MAIL_FROM` | ConfigMap | `contas@seudominio.com` |

## Sobre imagens Docker

**Não é necessário Artifact Registry do GCP.** O GKE puxa imagens de qualquer registry público (Docker Hub, GHCR, etc) sem configuração extra. O workflow publica em `loujrdocker/minhas-contas:<versão>` e o deployment referencia essa tag diretamente via `kubectl set image`.

Se você fosse usar imagem **privada**, precisaria criar um `imagePullSecret`:

```bash
kubectl create secret docker-registry dockerhub-cred \
  --namespace=minhas-contas \
  --docker-server=docker.io \
  --docker-username=SEU_USER \
  --docker-password=SEU_TOKEN
```

...e adicionar no `app-deployment.yaml`:

```yaml
spec:
  template:
    spec:
      imagePullSecrets:
        - name: dockerhub-cred
```

## Cleanup

```bash
# Apagar recursos k8s (Secret 'app-secrets' fica órfão, deletar junto):
kubectl delete -k sk8/
kubectl delete secret app-secrets -n minhas-contas

# Apagar cluster e IP:
gcloud container clusters delete minhas-contas-cluster --region=us-central1
gcloud compute addresses delete minhas-contas-ip --global

# (opcional) Apagar SA e suas chaves:
gcloud iam service-accounts delete gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com
```

## Troubleshooting rápido

| Sintoma | Causa provável | Correção |
|---|---|---|
| CI falha em `Get GKE Credentials` | `GKE_ZONE` é zona mas cluster é regional (ou vice-versa) | conferir `gcloud container clusters describe` e ajustar Secret |
| CI falha em `Authenticate to Google Cloud` | `GCP_SA_KEY` é de outra SA / outra org | regerar chave e atualizar Secret |
| Step *Ensure global static IP* com ERROR no log | esperado — mostra o output bruto do gcloud, o step continua verde | ignorar (é debug intencional) |
| Ingress sem `ADDRESS` | anotação `global-static-ip-name` não bate com o nome do IP | conferir workflow linha 161 vs ingress linha 19 |
| App pod em `CreateContainerConfigError` | Secret `app-secrets` não existe | próximo run do CI cria; ou rodar manualmente `kubectl -n minhas-contas create secret generic app-secrets ...` |
| App pod em `CrashLoopBackOff` com erro de conexão DB | senha no Secret divergem da inicialização inicial do PVC | apagar o PVC e deixar o Flyway recriar o schema (perde dados) OU restaurar backup |
| `kubectl apply -k sk8/` falha com "secret.yaml not found" | você tentou rodar o kustomize sem ter um `secret.yaml` local | ele está fora do `kustomization.yaml` de propósito; rode mesmo assim — `apply -k` funciona limpo |

## Checklist de segurança (infra)

- [ ] **Não commitar** `sk8/secret.yaml` com senha real (já está no `.gitignore` da linha 6, mas confirme se você criar o arquivo localmente).
- [ ] Trocar `COOKIE_SECURE` para `"true"` quando HTTPS estiver ativo.
- [ ] Configurar backup do Postgres antes de considerar produção (ver seção DR).
- [ ] Revogar a chave JSON local após colar no GitHub (`rm gke-deployer-key.json`).
- [ ] Considerar migrar para **Cloud SQL** se quiser eliminar o risco de perder dados junto com o PV local do cluster.
- [ ] Configurar domínio real em `sk8/ingress.yaml` antes de ativar `ManagedCertificate`.
