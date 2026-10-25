# Eventos da fábrica

Aplicação fullstack para cadastrar e consultar eventos industriais. Eventos PRODUCTION são registrados; eventos QUALITY também geram uma auditoria, na mesma transação. A API rejeita identificadores externos duplicados.

## Tecnologias e pré-requisitos

- Backend: JDK 25, Spring Boot 4.1.2, Maven Wrapper, Web MVC, JPA/Hibernate, Bean Validation, Flyway, Actuator/Micrometer e Springdoc OpenAPI.
- Banco: PostgreSQL 18, com Docker e Docker Compose.
- Frontend: Node.js 24, npm, React, TypeScript e Vite.
- Testes: JUnit/Testcontainers e Vitest/Testing Library/jsdom e Playwright/Chromium.

As versões das dependências Java são gerenciadas pelo Spring Boot. O frontend possui package-lock.json. Na primeira execução, é necessário acesso à internet para baixar dependências e imagens Docker.

## Estrutura do repositório

- `factory-events-backend/`: API Java e testes de integração.
- `factory-events-frontend/`: interface React e testes de interface.
- `insomnia.json`: collection de execução. 
- `scripts/`: execução dos testes E2E.
- `compose.yaml`: PostgreSQL local.

## Executar

```sh
git clone https://github.com/jhonatagarcia/factory-events.git
cd factory-events
```

### 1. PostgreSQL

Na raiz:

```sh
docker compose up -d --wait
```

Padrões locais: host `127.0.0.1`, porta `5432`, banco `factory_events_db`, usuário `factory`, senha `factory_local`. São credenciais exclusivamente de desenvolvimento.

Pode-se copiar `.env.example` para `.env` para configurar o Compose. Alterar POSTGRES_DB/USER/PASSWORD não modifica um banco já inicializado no volume. O backend não carrega `.env` automaticamente: se usar valores diferentes, exporte DB_URL, DB_USERNAME e DB_PASSWORD no terminal do backend.

### 2. Backend

Em outro terminal:

```sh
cd factory-events-backend
./mvnw spring-boot:run
```

No Windows, use `mvnw.cmd`. O backend atende em `http://localhost:8080`. Saúde: `GET /actuator/health`.

O Flyway aplica as migrações V1 (eventos/auditorias) e V2 (numeração manual/reenvios). O Hibernate valida as tabelas em vez de alterá-las. As migrações preservam os registros existentes.

### 3. Frontend

Em outro terminal:

```sh
cd factory-events-frontend
npm ci
npm run dev
```

Abra `http://localhost:5173`. O proxy do Vite encaminha `/api` para a porta 8080. Em publicação, configure um reverse proxy equivalente ou a URL da API e CORS.

A interface permite cadastrar, consultar em tabela, atualizar e navegar em páginas de 10 registros. Após cadastro, consulta novamente a primeira página. Falhas de consulta não apagam o sucesso do cadastro. Os horários são apresentados no fuso do navegador.

## API

| Método | Rota | Comportamento |
| --- | --- | --- |
| POST | `/api/events` | Recebe evento de equipamento com eventId; retorna 201 ou 409 |
| GET | `/api/events/{eventId}` | Consulta individual; 200 ou 404 |
| GET | `/api/events?page=0&size=20` | Consulta paginada |
| GET | `/api/events/manual/next-id` | Prévia do próximo código; não reserva número |
| POST | `/api/events/manual` | Cadastro manual com ID numérico gerado no banco |

### Evento de equipamento (Insomnia ou curl)

```sh
curl -i http://localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"EQUIP-1001","eventType":"QUALITY","description":"Inspeção concluída","timestamp":"2026-05-20T10:00:00-03:00"}'
```

Reenvio do mesmo eventId retorna 409 e não altera o original, mesmo com conteúdo diferente. O sucesso retorna o evento e Location com sua URL de consulta.

### Cadastro manual

```sh
curl -i http://localhost:8080/api/events/manual \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"0ee872ea-e379-46f5-9486-e6a9ad2ab702","eventType":"PRODUCTION","description":"Linha iniciada","timestamp":"2026-05-20T10:00:00-03:00"}'
```

O campo requestId é um UUID da tentativa. Repetir o mesmo requestId com os mesmos dados devolve o evento originalmente criado (201 e Location), sem duplicar evento, auditoria ou avanço do contador. Reutilizá-lo com outros dados retorna 409/MANUAL_REQUEST_CONFLICT. Para um cadastro novo, use outro UUID.

### Validação e erros

- Tipos: PRODUCTION e QUALITY.
- Identificador externo: até 100 caracteres; começa com letra/número e aceita letras ASCII, números, ponto, hífen e sublinhado. Diferencia maiúsculas de minúsculas.
- Descrição obrigatória, não composta apenas por espaços, até 2000 caracteres.
- Timestamp obrigatório em ISO 8601 com offset (`-03:00` ou `Z`). O exemplo original sem offset foi explicitamente tornado não ambíguo neste contrato.
- Paginação começa em zero; size entre 1 e 100. Ordenação por receivedAt decrescente e eventId crescente como desempate.
- Dados ou JSON inválidos: 400. Evento inexistente: 404. Duplicidade: 409. Falhas internas: 500.

Os erros principais usam ProblemDetail com `code` estável e `errors` para validação de campos. Stack traces e detalhes SQL ficam nos logs, não na resposta.

## Arquitetura e decisões

Frontend → controller → service transacional → repository → PostgreSQL.

O backend usa pacotes controller, service, repository, entity, model/dto, enums, exception, config e shared/error. O frontend separa EventForm, EventTable, contratos TypeScript e services/events-api.ts.

- **Unicidade:** a chave primária eventId impede duplicação mesmo com chamadas concorrentes. Criar evento usa persist explícito para não atualizar silenciosamente um registro existente. Somente a violação de unicidade da constraint pk_events é traduzida para conflito externo.
- **Atomicidade:** evento QUALITY e auditoria são confirmados ou desfeitos juntos. Flush envia SQL; commit confirma a transação.
- **Numeração manual:** uma linha de contador com SELECT FOR UPDATE coordena os cadastros manuais entre navegadores e instâncias. O contador avança dentro da mesma transação do evento, da auditoria e da identificação da tentativa. Falhas desfazem também seu avanço.
- **Formato:** EVT-00001, EVT-00002 etc., com pelo menos cinco dígitos. A numeração parte de um valor acima dos IDs numéricos existentes, incluindo formatos antigos como EVT-1001. Números já ocupados por equipamentos podem causar saltos; não se promete sequência global sem lacunas.
- **Prévia:** o formulário consulta uma sugestão somente leitura. Ela não reserva um número. Se outra pessoa cadastrar primeiro, o código efetivo pode mudar; a mensagem de sucesso mostra o ID confirmado.
- **Reenvios manuais:** requestId e hash do conteúdo reconhecem a repetição da mesma tentativa. O formulário mantém a chave após erro e só troca após confirmação. Ela fica no estado da tela; recarregar a página inicia uma tentativa nova.
- **Concorrência com equipamentos:** o endpoint externo não usa o bloqueio manual. Se um equipamento ocupar o mesmo ID entre a prévia e a inserção, a chave primária rejeita uma operação; uma nova tentativa manual recalcula o próximo número, sem consumir o contador na falha.
- **Datas:** ocorrência e recebimento são separados; instantes são persistidos com TIMESTAMPTZ. O formulário interpreta datetime-local no fuso do navegador e envia UTC.
- **Simplicidade:** processamento síncrono, switch para dois tipos, DTOs separados das entidades, CSS sem framework de interface e estado local React.

## Testes e build

Backend, com Docker funcionando (não precisa iniciar o banco do Compose):

```sh
cd factory-events-backend
./mvnw clean verify
```

Os testes criam PostgreSQL descartável com Testcontainers e exercitam HTTP real. Cobrem PRODUCTION/QUALITY, duplicidade, concorrência, rollback, paginação, validação, numeração manual e reenvios. A limpeza ocorre somente no container isolado. Não executar esses testes em paralelo, pois compartilham tabelas no mesmo container.

Frontend:

```sh
cd factory-events-frontend
npm ci
npm run lint
npm test
npm run build
```

Vitest e Testing Library verificam formulário, identificador somente leitura, preservação da tentativa após falha, recuperação de consultas, paginação e atualização da tabela após cadastro. Os testes simulam a API; a suíte do backend verifica PostgreSQL real.

Para desenvolver testes continuamente: `npm run test:watch`. O build gera factory-events-frontend/dist e factory-events-backend/target/factory-events-backend-0.0.1-SNAPSHOT.jar. Para executar o JAR, com banco disponível: `java -jar factory-events-backend/target/factory-events-backend-0.0.1-SNAPSHOT.jar`.

## Verificação manual

1. Cadastrar PRODUCTION: 201; sem auditoria.
2. Cadastrar QUALITY: 201; com auditoria.
3. Repetir eventId no endpoint externo: 409; sem nova gravação.
4. Enviar descrição vazia ou tipo inválido: 400.
5. Consultar evento inexistente: 404.
6. Cadastrar pela interface: confirmação com ID e tabela atualizada.
7. Usar Atualizar, Anterior e Próxima para consultar a lista.

No DBeaver, conectar a factory_events_db com as credenciais locais acima e consultar:

```sql
SELECT * FROM events ORDER BY received_at DESC;
SELECT * FROM event_audits ORDER BY created_at DESC;
```

## Problemas de execução

- Conexão recusada em 5432: verifique Docker e `docker compose ps`. Se o container não publicar a porta, use `docker compose up -d --force-recreate --wait postgres`, preservando o volume.
- Senha rejeitada: confira banco, usuário, senha e variáveis de ambiente; os padrões do Compose não redefinem credenciais em volumes existentes.
- Porta 8080 ocupada: pare a instância anterior ou configure SERVER_PORT e ajuste o proxy do Vite.
- No Insomnia, selecione o método no campo próprio e cole somente a URL, sem o prefixo GET/POST.

## Limitações e melhorias futuras

Não há autenticação por equipamento/usuário, filtros por período/tipo ou edição/exclusão. Melhorias possíveis: autenticação, filtros, retenção, exportação de métricas para monitoramento externo e expansão dos testes visuais. Paginação por offset pode deslocar itens quando novos eventos são inseridos entre consultas; cursor seria uma evolução. A chave de reenvio manual não é mantida após recarregar a página; persistir um rascunho com a tentativa seria outra melhoria. O contador serializa cadastros manuais, adequado para este teste, mas deve ser reavaliado em volumes elevados.

## Parar

Encerre os servidores com Ctrl+C e pare o banco com:

```sh
docker compose stop
```

O volume mantém os dados. Não use down -v para uma parada normal, pois remove o volume.

## Swagger / OpenAPI

Com o backend iniciado:

- Interface: http://localhost:8080/swagger-ui/index.html (ou /swagger-ui.html).
- Especificação JSON: http://localhost:8080/v3/api-docs.

A documentação descreve as operações de equipamentos, cadastro manual e consultas, incluindo respostas de erro. A especificação é gerada a partir dos controllers e DTOs, reduzindo divergência em relação ao código.

## Coleção do Insomnia

Importe [insomnia.json](insomnia.json) usando Import → File. A coleção contém ambiente local, cadastro PRODUCTION/QUALITY, duplicidade, validação, cadastro manual, consultas, saúde, métricas e OpenAPI.

O ambiente define base_url, event_id, quality_id e request_id. Para novos cadastros externos, altere os IDs. Para testar duplicidade, mantenha o mesmo ID. No cadastro manual, mantenha request_id e conteúdo para testar reenvio; para outro cadastro, use um UUID novo. A coleção usa apenas dados e credenciais locais de exemplo.

## Logs e observabilidade

Toda requisição recebe X-Correlation-ID. Um valor informado pelo cliente é aceito se tiver de 1 a 64 caracteres ASCII entre letras, números, ponto, hífen e sublinhado; caso contrário, a API gera UUID. O ID é incluído nos logs e nas respostas ProblemDetail. O MDC é limpo ao terminar a requisição para não contaminar outras chamadas da mesma thread.

Exemplo:

```sh
curl -i http://localhost:8080/api/events/INEXISTENTE -H 'X-Correlation-ID: entrevista-001'
```

Além da saúde, /actuator/metrics expõe métricas JVM/HTTP e contadores:

| Métrica | Significado |
| --- | --- |
| factory.events.registered | Eventos confirmados, por type e source (equipment/manual) |
| factory.events.duplicates | Duplicidades externas rejeitadas por pk_events |
| factory.events.replays | Reenvios manuais que devolveram o registro original |
| factory.api.requests | Requisições API por método e resultado: success/client_error/server_error |
| http.server.requests | Métrica HTTP padrão do Spring, incluindo duração |

Consulte /actuator/metrics/factory.events.registered. Contadores passam a existir após a primeira ocorrência e reiniciam com a aplicação: não são o total histórico do banco. Não usamos eventId, requestId, descrição ou URL bruta como tags, evitando cardinalidade sem limite. Os logs não incluem corpos de requisição nem senhas.

Erros HTTP menos comuns (parâmetro inválido, rota inexistente, método não permitido e mídia não suportada) também recebem ProblemDetail com code e correlationId, preservando cabeçalhos como Allow.

## Testes completos em navegador

Pré-requisitos adicionais: Chromium do Playwright e portas locais 55433, 18080 e 15173 livres.

```sh
cd factory-events-frontend
npm ci
npx playwright install chromium
npm run test:e2e
```

O script cria um PostgreSQL temporário em compose.e2e.yaml, compila o backend, inicia uma API separada na porta 18080 e um Vite na porta 15173. Não usa os registros do banco de desenvolvimento. Ao terminar, encerra a API E2E e remove o container temporário. Execute uma suíte E2E por vez.

O Playwright verifica desktop e celular: cadastro QUALITY por interface, consulta do registro na API, atualização da tabela, paginação, validação, ausência de overflow da página e screenshots. Após os testes, o script consulta o banco para conferir que QUALITY tem auditoria e PRODUCTION não tem. A tabela pode rolar horizontalmente em telas pequenas.

Relatório HTML: factory-events-frontend/playwright-report/index.html. Screenshots e traces: factory-events-frontend/test-results. Para abrir o relatório: `npx playwright show-report` dentro de factory-events-frontend. Em caso de falha de inicialização, consulte e2e-backend.log.

Veja [decisões técnicas](docs/DECISOES-TECNICAS.md) e [registro de validação](docs/VALIDACAO.md).
