# Frontend — Eventos da fábrica

React com TypeScript e Vite. Consulte [o README da raiz](../README.md) para arquitetura, execução, contratos e decisões.

```sh
npm ci
npm run dev
```

Abra http://localhost:5173 com o backend na porta 8080. Verificações:

```sh
npm run lint
npm test
npm run build
```

O formulário cadastra eventos manuais com ID gerado pelo backend; a tabela permite consulta, atualização e paginação. Testes em jsdom simulam a API.
