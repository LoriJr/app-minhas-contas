# Deploy no GKE — App Minhas Contas

## Pré-requisitos no GCP (Console / CLI)

### 1. Criar o projeto e habilitar APIs

```bash
# Se ainda não tiver um projeto, crie pelo console ou:
gcloud projects create minhas-contas-proj --name="Minhas Contas"
gcloud config set project minhas-contas-proj

# Habilitar as APIs necessárias
gcloud services enable container.googleapis.com
gcloud services enable compute.googleapis.com
```

### 2. Criar o Cluster GKE

```bash
# Cluster pequeno (custo ~$0.04/h = ~$29/mês com 1 nó e2-small)
gcloud container clusters create minhas-contas-cluster \
  --region=us-central1 \
  --num-nodes=1 \
  --machine-type=e2-small \
  --enable-ip-alias \
  --no-enable-autoupgrade
```

> **Dica:** Para reduzir custos, considere um cluster autopolotado (`--autoprovisioning-image-type=COS`)
> ou use Cloud Run + Cloud SQL como alternativa serverless.

### 3. Criar IP Estático Global

O Ingress do GKE precisa de um IP fixo para apontar o DNS:

```bash
gcloud compute addresses create minhas-contas-ip --global
gcloud compute addresses describe minhas-contas-ip --global --format='value(address)'
```

Anote o IP retornado e configure a entrada **A** no seu DNS apontando para ele.

### 4. Autenticar kubectl no Cluster

```bash
gcloud container clusters get-credentials minhas-contas-cluster \
  --region=us-central1
```

---

## Deploy da Aplicação

### Ordem de aplicação (ou use kustomize):

```bash
# Aplicar tudo de uma vez
kubectl apply -k sk8/

# Ou individualmente (nesta ordem):
kubectl apply -f sk8/namespace.yaml
kubectl apply -f sk8/configmap.yaml
kubectl apply -f sk8/secret.yaml        # ⚠️ edite com valores reais antes!
kubectl apply -f sk8/postgres.yaml
kubectl apply -f sk8/app-deployment.yaml
kubectl apply -f sk8/app-service.yaml
kubectl apply -f sk8/ingress.yaml
```

### Criar o Secret corretamente (recomendado via CLI):

```bash
kubectl create secret generic app-secrets \
  --namespace=minhas-contas \
  --from-literal=DATABASE_PASSWORD='sua-senha-forte-aqui' \
  --from-literal=SMTP_PASSWORD=''
```

---

## Sobre Imagens do Docker Hub

**Não é necessário configurar Artifact Registry do GCP.** O GKE consegue
puxar imagens de qualquer registry público (Docker Hub, GHCR, etc) sem
configuração extra. O workflow CI/CD já publica em `loujrdocker/minhas-contas:latest`
e o deployment referencia essa tag diretamente.

Se a imagem fosse **privada**, seria necessário criar um imagePullSecret:

```bash
kubectl create secret docker-registry dockerhub-cred \
  --namespace=minhas-contas \
  --docker-server=docker.io \
  --docker-username=SEU_USER \
  --docker-password=SEU_TOKEN
```

...e adicionar no deployment:

```yaml
spec:
  template:
    spec:
      imagePullSecrets:
        - name: dockerhub-cred
```

---

## Variáveis de Ambiente (resumo)

| Variável            | Origem       | Exemplo                                    |
|---------------------|--------------|--------------------------------------------|
| DATABASE_URL        | ConfigMap    | jdbc:postgresql://postgres:5432/fatura      |
| DATABASE_USER       | ConfigMap    | fatura                                      |
| DATABASE_PASSWORD   | Secret       | (senha forte)                               |
| COOKIE_SECURE       | ConfigMap    | true (produção HTTPS)                       |
| MAIL_ENABLED        | ConfigMap    | false                                       |
| SMTP_HOST           | ConfigMap    | smtp.gmail.com                              |
| SMTP_PORT           | ConfigMap    | 587                                         |
| SMTP_USER           | ConfigMap    | seu-email@gmail.com                         |
| SMTP_PASSWORD       | Secret       | (app password)                              |
| SMTP_AUTH           | ConfigMap    | true                                        |
| MAIL_FROM           | ConfigMap    | contas@seudominio.com                       |

---

## Deploy Automático (CI/CD)

O workflow já faz o deploy automaticamente: **push na `main`** → build da imagem →
autentica no GCP → aplica manifests → rollout restart. Zero intervenção manual.

### Secrets necessários no GitHub (Settings → Secrets → Actions)

| Secret | Onde obter | Exemplo |
|--------|-----------|---------|
| `DOCKER_USERNAME` | Username do Docker Hub | `loujrdocker` |
| `DOCKER_ACCESS_TOKEN` | Docker Hub → Account Settings → Personal Access Tokens | `dckr_pat_xxx...` |
| `GCP_SA_KEY` | Chave JSON do Service Account (instruções abaixo) | `{"type":"service_account",...}` |
| `GCP_PROJECT_ID` | ID do projeto GCP | `minhas-contas-proj` |
| `GKE_CLUSTER` | Nome do cluster | `minhas-contas-cluster` |
| `GKE_ZONE` | Zona do cluster | `us-central1-a` |

### Criar a Service Account para o CI/CD

```bash
# 1. Criar a service account
gcloud iam service-accounts create gke-deployer \
  --display-name="GKE Deployer (CI/CD)"

# 2. Dar permissão de editar o cluster (role mínimo necessário)
gcloud container clusters add-iam-policy-binding minhas-contas-cluster \
  --region=us-central1 \
  --member="serviceAccount:gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/container.developer"

# 3. Gerar a chave JSON
gcloud iam service-accounts keys create gke-deployer-key.json \
  --iam-account=gke-deployer@SEU_PROJECT_ID.iam.gserviceaccount.com
```

Cole o conteúdo de `gke-deployer-key.json` no secret `GCP_SA_KEY` do GitHub.

> **Dica de segurança:** depois de colar no GitHub, apague o arquivo JSON local.
> A chave pode ser revogada a qualquer momento pelo console do GCP.

### Rollback manual (se necessário)

```bash
kubectl rollout undo deployment/minhas-contas -n minhas-contas
```

---

## Verificar Logs e Status

```bash
# Pods rodando?
kubectl get pods -n minhas-contas

# Logs da aplicação
kubectl logs -f deployment/minhas-contas -n minhas-contas

# Logs do banco
kubectl logs -f deployment/postgres -n minhas-contas

# Descrever pod (para debug de crashes)
kubectl describe pod -n minhas-contas -l app=minhas-contas
```

---

## Cleanup

```bash
kubectl delete -k sk8/
gcloud container clusters delete minhas-contas-cluster --region=us-central1
gcloud compute addresses delete minhas-contas-ip --global
```

---

## Checklist de Segurança

- [ ] Trocar senha padrão no Secret (nunca usar "123456")
- [ ] Remover `secret.yaml` do git ou usar SOPS/Sealed Secrets
- [ ] Configurar domínio real no `ingress.yaml` antes do ManagedCertificate
- [ ] Ativar Cloud SQL se quiser banco gerenciado (elimina PVC + manutenção)
