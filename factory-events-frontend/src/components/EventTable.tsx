import { useEffect, useState } from 'react'
import { ApiError, listEvents } from '../services/events-api'
import type { EventPageResponse } from '../model/event'

const PAGE_SIZE = 10
const dateFormatter = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short', timeStyle: 'medium',
})

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Data indisponível' : dateFormatter.format(date)
}

export function EventTable({ revision }: { revision: number }) {
  const [query, setQuery] = useState({ page: 0, revision, refresh: 0 })
  const [result, setResult] = useState<{
    query: typeof query; data: EventPageResponse | null; error: string | null
  } | null>(null)
  const loading = result?.query !== query
  const data = result?.data
  const error = loading ? null : result?.error

  // Reset pagination as part of the render when a registration is confirmed.
  if (query.revision !== revision) {
    setQuery({ ...query, page: 0, revision })
  }

  useEffect(() => {
    const controller = new AbortController()
    listEvents(query.page, PAGE_SIZE, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return
        if (result.totalPages > 0 && query.page >= result.totalPages) {
          setQuery((current) => ({ ...current, page: result.totalPages - 1 }))
          return
        }
        setResult({ query, data: result, error: null })
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return
        setResult({ query, data: null, error: cause instanceof ApiError ? cause.message : 'Não foi possível consultar os eventos. Tente novamente.' })
      })

    return () => controller.abort()
  }, [query])

  function refresh() {
    setQuery((current) => ({ ...current, refresh: current.refresh + 1 }))
  }

  return (
    <section className="event-card events-section" aria-labelledby="events-title" aria-busy={loading}>
      <header className="card-heading">
        <div>
          <h2 id="events-title">Eventos registrados</h2>
          <p>Consulte as ocorrências mais recentes. Horários no fuso do navegador.</p>
        </div>
        <button className="secondary-button" type="button" disabled={loading} onClick={refresh}>
          {loading ? 'Atualizando…' : 'Atualizar'}
        </button>
      </header>

      {loading ? (
        <p className="table-state" role="status">Consultando eventos…</p>
      ) : error ? (
        <div className="table-state" role="alert">
          <p>{error}</p>
          <button className="secondary-button" type="button" onClick={refresh}>Tentar novamente</button>
        </div>
      ) : data && data.items.length > 0 ? (
        <>
          <div className="table-scroll" role="region" aria-label="Tabela de eventos" tabIndex={0}>
            <table className="events-table">
              <caption className="sr-only">Eventos ordenados pelo recebimento mais recente</caption>
              <thead>
                <tr>
                  <th scope="col">Identificador</th>
                  <th scope="col">Tipo</th>
                  <th scope="col">Descrição</th>
                  <th scope="col">Ocorrência</th>
                  <th scope="col">Recebimento</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((event) => (
                  <tr key={event.eventId}>
                    <th scope="row" className="event-code">{event.eventId}</th>
                    <td><span className={`event-type event-type--${event.eventType.toLowerCase()}`}>
                      {event.eventType === 'QUALITY' ? 'Qualidade' : 'Produção'}
                    </span></td>
                    <td className="event-description">{event.description}</td>
                    <td><time dateTime={event.timestamp}>{formatDate(event.timestamp)}</time></td>
                    <td><time dateTime={event.receivedAt}>{formatDate(event.receivedAt)}</time></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav className="pagination" aria-label="Paginação dos eventos">
            <span>{data.totalElements} {data.totalElements === 1 ? 'evento' : 'eventos'} · Página {data.page + 1} de {data.totalPages}</span>
            <div>
              <button className="secondary-button" type="button" disabled={data.page === 0}
                onClick={() => setQuery((current) => ({ ...current, page: current.page - 1 }))}>Anterior</button>
              <button className="secondary-button" type="button" disabled={data.page + 1 >= data.totalPages}
                onClick={() => setQuery((current) => ({ ...current, page: current.page + 1 }))}>Próxima</button>
            </div>
          </nav>
        </>
      ) : (
        <div className="table-state"><h3>Nenhum evento registrado</h3><p>Cadastre a primeira ocorrência no formulário acima.</p></div>
      )}
    </section>
  )
}
