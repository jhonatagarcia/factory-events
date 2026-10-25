import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventForm } from './EventForm'
import { EventTable } from './EventTable'
import App from '../App'
import { ApiError, createManualEvent, getNextEventId, listEvents } from '../services/events-api'
import type { EventPageResponse, EventResponse } from '../model/event'

vi.mock('../services/events-api', async (importOriginal) => {
  const original = await importOriginal<typeof import('../services/events-api')>()
  return { ...original, createManualEvent: vi.fn(), getNextEventId: vi.fn(), listEvents: vi.fn() }
})

const event: EventResponse = {
  eventId: 'EVT-00003', eventType: 'QUALITY', description: 'Inspeção concluída',
  timestamp: '2026-05-20T13:00:00Z', receivedAt: '2026-05-20T13:01:00Z',
}
const empty: EventPageResponse = { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }
const populated: EventPageResponse = { ...empty, items: [event], totalElements: 1, totalPages: 1 }

beforeEach(() => {
  vi.mocked(getNextEventId).mockReset().mockResolvedValue({ eventId: 'EVT-00003' })
  vi.mocked(createManualEvent).mockReset().mockResolvedValue(event)
  vi.mocked(listEvents).mockReset().mockResolvedValue(empty)
})

async function fillForm() {
  const user = userEvent.setup()
  await waitFor(() => expect(screen.getByLabelText(/Identificador/)).toHaveValue('EVT-00003'))
  await user.type(screen.getByLabelText(/^Descrição/), 'Inspeção concluída')
  fireEvent.change(screen.getByLabelText(/Data e hora/), { target: { value: '2026-05-20T10:00:00' } })
  return user
}

describe('Cadastro manual', () => {
  it('exibe o ID somente leitura e atualiza depois do sucesso', async () => {
    vi.mocked(getNextEventId).mockResolvedValueOnce({ eventId: 'EVT-00003' })
      .mockResolvedValue({ eventId: 'EVT-00004' })
    const onCreated = vi.fn()
    render(<EventForm onCreated={onCreated} />)
    const user = await fillForm()
    expect(screen.getByLabelText(/Identificador/)).toHaveAttribute('readonly')
    await user.click(screen.getByRole('button', { name: /Registrar evento/ }))
    expect(await screen.findByRole('status')).toHaveTextContent('EVT-00003')
    await waitFor(() => expect(screen.getByLabelText(/Identificador/)).toHaveValue('EVT-00004'))
    expect(screen.getByLabelText(/^Descrição/)).toHaveValue('')
    expect(onCreated).toHaveBeenCalledTimes(1)
    expect(vi.mocked(createManualEvent).mock.calls[0][0]).not.toHaveProperty('eventId')
  })

  it('preserva dados e a chave da tentativa após falha de comunicação', async () => {
    vi.mocked(createManualEvent).mockRejectedValueOnce(new TypeError('network error'))
    render(<EventForm />)
    const user = await fillForm()
    await user.click(screen.getByRole('button', { name: /Registrar evento/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('comunicar com a API')
    expect(screen.getByLabelText(/^Descrição/)).toHaveValue('Inspeção concluída')
    expect(screen.getByLabelText(/Identificador/)).toHaveValue('EVT-00003')
    const first = vi.mocked(createManualEvent).mock.calls[0][0]
    await user.click(screen.getByRole('button', { name: /Registrar evento/ }))
    await waitFor(() => expect(createManualEvent).toHaveBeenCalledTimes(2))
    expect(vi.mocked(createManualEvent).mock.calls[1][0].requestId).toBe(first.requestId)
  })

  it('inicia outra tentativa explicitamente após conflito de conteúdo', async () => {
    vi.mocked(createManualEvent).mockRejectedValueOnce(new ApiError(409, 'Tentativa já utilizada', {
      code: 'MANUAL_REQUEST_CONFLICT',
    }))
    render(<EventForm />)
    const user = await fillForm()
    await user.click(screen.getByRole('button', { name: /Registrar evento/ }))
    await screen.findByRole('alert')
    const first = vi.mocked(createManualEvent).mock.calls[0][0].requestId
    await user.click(screen.getByRole('button', { name: /Iniciar novo cadastro/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Registrar evento/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /Registrar evento/ }))
    await waitFor(() => expect(createManualEvent).toHaveBeenCalledTimes(2))
    expect(vi.mocked(createManualEvent).mock.calls[1][0].requestId).not.toBe(first)
  })

  it('permite repetir uma consulta de identificador que falhou', async () => {
    vi.mocked(getNextEventId).mockRejectedValueOnce(new Error('offline'))
    render(<EventForm />)
    expect(await screen.findByRole('alert')).toHaveTextContent('próximo identificador')
    expect(screen.getByRole('button', { name: /Registrar evento/ })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /Tentar novamente/ }))
    await waitFor(() => expect(screen.getByLabelText(/Identificador/)).toHaveValue('EVT-00003'))
  })
})

describe('Consulta', () => {
  it('distingue lista vazia de falha e permite nova tentativa', async () => {
    vi.mocked(listEvents).mockRejectedValueOnce(new ApiError(500, 'Consulta indisponível'))
    render(<EventTable revision={0} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Consulta indisponível')
    expect(screen.queryByText('Nenhum evento registrado')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Tentar novamente/ }))
    expect(await screen.findByText('Nenhum evento registrado')).toBeInTheDocument()
  })

  it('exibe registros e navega entre páginas', async () => {
    vi.mocked(listEvents).mockImplementation(async (page = 0) => ({
      ...populated, page, totalElements: 11, totalPages: 2,
      items: [{ ...event, eventId: page === 0 ? 'EVT-00003' : 'EVT-00002' }],
    }))
    render(<EventTable revision={0} />)
    expect(await screen.findByText('EVT-00003')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }))
    expect(await screen.findByText('EVT-00002')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()
  })

  it('retorna à primeira página quando um cadastro é confirmado', async () => {
    vi.mocked(listEvents).mockImplementation(async (page = 0) => ({
      ...populated, page, totalElements: 11, totalPages: 2,
    }))
    const { rerender } = render(<EventTable revision={0} />)
    await screen.findByText('EVT-00003')
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }))
    await screen.findByText(/Página 2 de 2/)
    rerender(<EventTable revision={1} />)
    expect(await screen.findByText(/Página 1 de 2/)).toBeInTheDocument()
  })

  it('ignora resposta antiga depois que outra consulta a substitui', async () => {
    let finishOld!: (value: EventPageResponse) => void
    vi.mocked(listEvents).mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve }))
      .mockResolvedValue(populated)
    const { rerender } = render(<EventTable revision={0} />)
    rerender(<EventTable revision={1} />)
    await screen.findByText('EVT-00003')
    finishOld(empty)
    await waitFor(() => expect(screen.getByText('EVT-00003')).toBeInTheDocument())
    expect(screen.queryByText('Nenhum evento registrado')).not.toBeInTheDocument()
  })

  it('atualiza a tabela depois de cadastrar pelo formulário', async () => {
    vi.mocked(listEvents).mockResolvedValueOnce(empty).mockResolvedValue(populated)
    render(<App />)
    await screen.findByText('Nenhum evento registrado')
    const user = await fillForm()
    await user.click(screen.getByRole('button', { name: /Registrar evento/ }))
    expect(await screen.findByRole('rowheader', { name: 'EVT-00003' })).toBeInTheDocument()
  })
})
