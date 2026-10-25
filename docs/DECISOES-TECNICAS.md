# Decisões para apresentar na entrevista

## Identidade e reenvios

O eventId identifica o fato enviado pelo equipamento. A chave primária resolve duplicidade inclusive entre instâncias. Uma verificação anterior com exists não seria garantia sob concorrência. No cadastro manual, a API gera EVT com numeração coordenada no banco; requestId identifica a tentativa para que a perda da resposta não provoque novo evento ao reenviar. Reutilizar requestId com outro conteúdo é conflito.

## Atomicidade e numeração

O service de cadastro inicia a transação; evento e auditoria são confirmados juntos. O fluxo manual bloqueia uma linha com SELECT FOR UPDATE e atualiza o contador na mesma transação. Um erro na auditoria desfaz evento, tentativa e avanço do contador. A prévia do ID não é reserva. A serialização dos cadastros manuais é simples para este volume; não foi adotada como solução de alta vazão.

## Observabilidade

Um filtro cria/valida a correlação e a registra no MDC. A mesma referência aparece no cabeçalho, nos erros e nos logs. O finally garante limpeza do contexto. Os contadores de registro são atualizados no controller depois do retorno do service transacional (commit concluído). Reenvio manual possui contador separado e não infla novos registros. Métricas são operacionais, em memória, e não substituem tabelas de negócio ou auditoria.

## Exceções e HTTP

As regras lançam exceções; o advice traduz para HTTP. A base ResponseEntityExceptionHandler preserva semântica e cabeçalhos do Spring. A customização acrescenta code e correlationId e remove detalhes internos de mensagens de parâmetros/rotas. Só a violação específica de pk_events vira duplicidade externa; outras falhas de integridade continuam sendo falhas internas.

## Testes em camadas

Testing Library simula a API para verificar comportamento do React sem depender do servidor. JUnit/Testcontainers testa HTTP e transações contra PostgreSQL real. Playwright foi configurado com backend e banco exclusivos para validar navegador → proxy → API → banco → resposta, em desktop e celular. Não é preciso mockar o banco para cumprir um diferencial: o tipo de teste deve provar a propriedade desejada.

## Documentação

Springdoc 3.1.1 atende à geração OpenAPI para Spring Boot 4.1. A especificação vem dos contratos Java e pode ser importada por clientes. A coleção Insomnia organiza uma demonstração reproduzível. Ambas complementam o README, que explica execução, premissas e limitações.

Referências: [Springdoc](https://springdoc.org/), [Playwright webServer](https://playwright.dev/docs/test-webserver).
