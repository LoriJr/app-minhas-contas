# Fatura

Gerenciador de contas mensais em português: React 19 + Vite, Java 21 + Spring Boot 3.5 + Spring Security e PostgreSQL 17 com Flyway. Interface responsiva web, com manifesto para adicionar à tela inicial do celular. Requer conexão à internet.

## Executar

Instale Docker e Docker Compose. Copie `.env.example` para `.env`, defina uma senha exclusiva para o banco e execute:

```sh
docker compose up --build
```

Abra http://localhost:8080. Crie um usuário com senha de 12 a 72 bytes UTF-8 (no mínimo 12 caracteres). Não existem credenciais padrão. Frontend e API são servidos pela mesma origem.

Para desenvolvimento: configure PostgreSQL, DATABASE_PASSWORD e COOKIE_SECURE=false; execute `mvn spring-boot:run` em backend. No frontend execute `npm ci` e `npm run dev`. O Vite encaminha /api para localhost:8080.

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

O painel mostra contas próximas do vencimento e vencidas. Para e-mails, configure MAIL_ENABLED=true, SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD e MAIL_FROM no servidor. STARTTLS está habilitado.

O agendador roda a cada 15 minutos, no fuso America/Sao_Paulo. Desde a antecedência escolhida até o registro do pagamento, tenta enviar um aviso por data para cada ocorrência mensal. Também prepara o próximo mês para lembrar, por exemplo, de uma conta que vence no dia 1. Falhas de envio são tentadas novamente no próximo ciclo. As credenciais do SMTP ficam no servidor. Não há SMS, push nem alarme sonoro em segundo plano nesta versão.

O controle de envio e o registro de pagamento são separados por mês. O bloqueio por usuário evita alterações concorrentes durante a operação, mas uma queda após o SMTP aceitar uma mensagem e antes do commit pode duplicar o aviso; para garantias adicionais, adote outbox/fila e idempotência no provedor. O envio depende do servidor ativo e do SMTP disponível.

## Segurança

Spring Security com sessão, senha BCrypt, proteção CSRF, cookie HttpOnly e acesso aos dados limitado ao proprietário. Para publicar, use HTTPS e COOKIE_SECURE=true, mantenha o banco privado e configure backup. Recuperação/verificação de e-mail, limitação de tentativas, auditoria e fluxos de privacidade/exclusão da conta de usuário ainda precisam ser desenvolvidos antes de abrir o serviço ao público.

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

Os testes Java incluem segurança da API, recorrência, independência dos pagamentos, alterações de valor/vencimento, preservação do histórico e exclusão. Os testes PostgreSQL são opcionais e exigem **um banco exclusivo e descartável de testes**, informado por TEST_DATABASE_URL, TEST_DATABASE_USER e TEST_DATABASE_PASSWORD. Com essas variáveis, `mvn test` também verifica a migração de dados legados, índices, persistência, isolamento entre usuários e lembretes. Os dados desses testes são revertidos; a migração usa e remove um schema temporário exclusivo.

Validação em 06/11/2026: compilação do frontend, 6 testes Node e 19 testes Java (incluindo 6 testes com PostgreSQL 17) concluídos com sucesso. Navegação real, SMTP real e hospedagem não foram verificados.
