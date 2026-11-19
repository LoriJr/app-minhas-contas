# Pasta `sk8/` — Manifests Kubernetes

Toda a documentação de **deploy no GKE (CI/CD)** está no **[README raiz do projeto](../README.md#deploy-no-gke-cicd)** — incluindo:

- [Checklist de nomes](../README.md#checklist-de-nomes-leia-primeiro) (fixos vs flexíveis)
- [Secrets no GitHub](../README.md#secrets-no-github) (os 8 obrigatórios)
- [Setup inicial do zero](../README.md#setup-inicial-do-zero-primeira-vez-15-min)
- [Recuperação de desastre](../README.md#recuperação-de-desastre-dr)
- [Operação e debug](../README.md#operação-e-debug)
- [Troubleshooting](../README.md#troubleshooting-rápido)

## Arquivos desta pasta

| Arquivo | O que faz |
|---|---|
| `namespace.yaml` | namespace único `minhas-contas` |
| `configmap.yaml` | ConfigMap `app-config` com variáveis não-sensíveis |
| `postgres.yaml` | PVC + Deployment + Service do Postgres 17 |
| `app-deployment.yaml` | Deployment da app Spring Boot (init container espera o Postgres) |
| `app-service.yaml` | Service ClusterIP (porta 80 → containerPort 8080) |
| `ingress.yaml` | Ingress GCE + ManagedCertificate (HTTPS opcional) |
| `kustomization.yaml` | agrupa os resources p/ `kubectl apply -k sk8/` |

> O Secret real `app-secrets` é criado pelo CI/CD a partir dos GitHub Secrets `DATABASE_PASSWORD` e `SMTP_PASSWORD` (step *Ensure app-secrets exists* no workflow). `secret.yaml` está fora do kustomization e do versionamento — é placeholder local apenas.
