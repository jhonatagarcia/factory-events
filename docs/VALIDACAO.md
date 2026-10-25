# Registro de validação

Data: 25/10/2026.

- Backend: 28 testes de integração passaram com PostgreSQL em Testcontainers; verify gerou o JAR.
- Frontend: 9 testes Testing Library/Vitest passaram; lint e build passaram.
- Swagger/OpenAPI: testes verificam a especificação e o carregamento do HTML do Swagger.
- Observabilidade: testes verificam correlação, substituição de IDs inválidos, isolamento entre requisições e contadores sem duplicar reenvios.
- HTTP: testes verificam 400, 404, 405 e 415 padronizados e preservação de Allow.
- Playwright: suíte preparada para desktop e celular; execução pendente de autorização do acesso ao navegador.

Os testes de integração e E2E usam bancos temporários separados do banco de desenvolvimento. Screenshots não foram tratados como aprovados sem inspeção visual.
