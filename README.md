# Fatura

MVP de gerenciamento de contas pessoais em português. React 19 + Vite; Java 21 + Spring Boot 3.5 + Spring Security; PostgreSQL 17 + Flyway. Uma aplicação responsiva web, com manifesto para adicionar à tela inicial do celular. Não é um aplicativo nativo nem funciona offline.

## Executar

Instale Docker e Docker Compose. Copie `.env.example` para `.env`, defina uma senha exclusiva para o banco e execute:

```sh
docker compose up --build
```

Abra http://localhost:8080. Crie um usuário com senha de 12 a 72 caracteres. Não há credenciais padrão. O frontend e a API são servidos pela mesma origem. A primeira compilação baixa dependências.

Para desenvolvimento separado: Java 21 e Maven 3.9, PostgreSQL disponível; exporte DATABASE_PASSWORD e COOKIE_SECURE=false, então execute `mvn spring-boot:run` em backend. Em frontend execute `npm install` e `npm run dev`. O Vite encaminha /api para localhost:8080.

## O que está implementado

- Cadastro e login via sessão do Spring Security, senha BCrypt, proteção CSRF, cookie HttpOnly e acesso às contas limitado ao proprietário.
- Criar, editar, excluir e consultar contas: empresa de serviço público, aluguel, garagem, condomínio e outras.
- Valores decimais no banco, vencimento, lembrete por conta, busca, filtros e calendário em lista por mês.
- Registro **manual** de pagamento e reabertura; nenhuma transferência é executada.
- Código original de pagamento armazenado por usuário. Para Pix copia e cola, QR Code gerado a partir do texto exato, tanto no frontend quanto no endpoint autenticado. Linha digitável pode ser guardada e copiada; não há imagem de código de barras nesta versão.
- Painel de lembretes calculado ao abrir o app; servidor consulta a cada 15 minutos e envia e-mail no máximo uma vez por data após envio bem-sucedido, da antecedência selecionada até o registro de pagamento. Contas vencidas continuam gerando aviso. Fuso America/Sao_Paulo.
- Demonstração com dados fictícios no localStorage, separada da API. A conta demo não fornece autenticação e não envia mensagens.

## Limite de autorização de pagamentos

O app **não tem integração contratada com Enel, Sabesp, Claro ou bancos**. Ele não emite um boleto ou Pix em nome dessas empresas. O campo origem é informado pelo usuário e não certifica autenticidade. Somente códigos recebidos do emissor/beneficiário devem ser cadastrados. O QR Code é uma representação do conteúdo fornecido e não comprova autorização. Confira beneficiário e valor no banco; a confirmação no app é manual. Não há verificação de CRC Pix ou dígitos verificadores de boletos nesta versão.

Para emissão autorizada e confirmação automática é necessário contratar um PSP/banco ou APIs oficiais de cada emissor, implementar credenciais no servidor, autorização de cada titular, adaptadores e validação de assinatura/idempotência dos webhooks. Nenhum provedor fictício está habilitado. Para aluguel próprio, a emissão depende da autorização e conta do beneficiário no PSP.

## Lembretes e mobile

Defina MAIL_ENABLED=true e configure SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD e MAIL_FROM no ambiente do servidor. STARTTLS está habilitado. O envio depende do servidor em execução; falhas são tentadas novamente no ciclo seguinte. Uma única instância do agendador é suportada. Em caso de queda após envio e antes de salvar pode haver duplicação. Para múltiplas instâncias, use outbox/fila com bloqueio distribuído. Não há notificações push nem alarme sonoro em segundo plano. No iOS/Android, use a opção do navegador para adicionar à tela inicial; funcionamento requer rede.

## Testes

`cd backend && mvn test`: acesso anônimo, proteção CSRF, consulta limitada ao dono e rejeição de QR de outro usuário.

`cd frontend && npm run build`: valida o empacotamento React.

Neste ambiente, a instalação não pôde ser concluída porque o proxy de rede estava inacessível. A sintaxe JSX/JSON do frontend e o XML do POM foram verificados com sucesso. Compilação, testes, execução em navegador e entrega em hospedagem **não foram verificados**. Os utilitários Sites também não estão instalados; Sites usa Workers e não hospeda a JVM. Use um host de containers/JVM para executar o projeto completo.

## Antes de divulgar para outros usuários

Esta é uma base MVP, não uma declaração de prontidão de produção. Configure domínio HTTPS, COOKIE_SECURE=true, proxy reverso e backup com restauração testada. Não exponha o Postgres. Adicione recuperação/verificação de e-mail, limitação de tentativas no login/cadastro, termos e política de privacidade LGPD, exclusão/exportação de dados, observabilidade, testes de integração reais com Postgres/SMTP e revisão de segurança. O projeto não contém recuperação de senha, recorrência automática, anexos de documentos, auditoria de alterações ou conciliação bancária. Alertas de e-mail podem expor título/valor ao provedor de e-mail: informe isso na política de privacidade.

As contas são registros avulsos. Para uma conta mensal, cadastre cada vencimento. Não há débito automático.
