import { test, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'

// All requests reach the real Spring Boot API and disposable PostgreSQL via Vite.
test.beforeAll(async ({ request }) => {
  for (let index = 0; index < 22; index++) {
    const response = await request.post('/api/events', { data: {
      eventId: `E2E-${randomUUID()}`,
      eventType: index % 2 === 0 ? 'PRODUCTION' : 'QUALITY',
      description: `Ocorrência de teste ${index + 1}`,
      timestamp: '2026-05-20T10:00:00-03:00',
    } })
    expect(response.status()).toBe(201)
  }
})

test('cadastra QUALITY e mostra o mesmo evento na tabela e na API', async ({ page, request }) => {
  const description = `Inspeção E2E ${randomUUID()}`
  await page.goto('/')
  await expect(page.getByLabel(/Identificador/)).toHaveValue(/^EVT-\d{5,}$/)
  await expect(page.getByLabel(/Identificador/)).toHaveAttribute('readonly', '')
  await page.getByLabel(/Tipo do evento/).selectOption('QUALITY')
  await page.getByLabel(/^Descrição/).fill(description)
  await page.getByLabel(/Data e hora da ocorrência/).fill('2026-05-20T10:00:00')
  await page.getByRole('button', { name: 'Registrar evento' }).click()
  const status = page.getByRole('status').filter({ hasText: /cadastrado com sucesso/ })
  await expect(status).toBeVisible()
  const id = (await status.innerText()).match(/EVT-\d+/)![0]
  await expect(page.getByRole('cell', { name: description, exact: true })).toBeVisible()
  const response = await request.get(`/api/events/${id}`)
  expect(response.status()).toBe(200)
  expect(await response.json()).toMatchObject({ eventId: id, eventType: 'QUALITY', description })
})

test('navega pelas páginas, atualiza a lista e permite rolagem da tabela no celular', async ({ page }) => {
  await page.goto('/')
  const first = page.getByRole('rowheader').first()
  await expect(first).toBeVisible()
  const firstId = await first.innerText()
  await expect(page.getByRole('button', { name: 'Anterior', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: 'Próxima', exact: true }).click()
  await expect(page.getByRole('rowheader').first()).not.toHaveText(firstId)
  await page.getByRole('button', { name: 'Anterior', exact: true }).click()
  await expect(page.getByRole('rowheader').first()).toHaveText(firstId)
  await page.getByRole('button', { name: 'Atualizar', exact: true }).click()
  await expect(page.getByRole('rowheader').first()).toHaveText(firstId)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await expect(page.getByRole('region', { name: 'Tabela de eventos' })).toBeVisible()
})

test('valida descrição e exibe erro real sem perder o formulário', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByLabel(/Identificador/)).toHaveValue(/^EVT-/)
  await page.getByLabel(/^Descrição/).fill('   ')
  await page.getByLabel(/Data e hora da ocorrência/).fill('2026-05-20T10:00:00')
  await page.getByRole('button', { name: 'Registrar evento' }).click()
  await expect(page.getByRole('alert')).toContainText('Preencha todos os campos')
  await expect(page.getByLabel(/^Descrição/)).toHaveValue('   ')
})

test('confere o layout com formulário e tabela carregados', async ({ page }, testInfo) => {
  await page.goto('/')
  await expect(page.getByRole('rowheader').first()).toBeVisible()
  await expect(page.getByLabel(/Identificador/)).toHaveValue(/^EVT-/)
  await expect(page.getByRole('heading', { name: 'Eventos registrados' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('pagina-completa.png'), fullPage: true })
})
