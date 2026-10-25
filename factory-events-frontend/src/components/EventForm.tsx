import { useEffect, useState } from 'react'
import type { SubmitEvent } from 'react'

import { ApiError, createManualEvent, getNextEventId } from '../services/events-api'
import type { EventType } from '../model/event'

interface EventFormProps {
  onCreated?: () => void
}

interface FormValues {
  eventId: string
  eventType: EventType
  description: string
  timestamp: string
}

function createInitialValues(): FormValues {
  return { eventId: '', eventType: 'PRODUCTION', description: '', timestamp: '' }
}

export function EventForm({ onCreated }: EventFormProps) {
  const [values, setValues] = useState<FormValues>(createInitialValues)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [requestConflict, setRequestConflict] = useState(false)

  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  const [idRevision, setIdRevision] = useState(0)
  const [identifier, setIdentifier] = useState<{ revision: number; error: string | null } | null>(null)
  const idLoading = identifier?.revision !== idRevision
  const idError = idLoading ? null : identifier?.error

  useEffect(() => {
    const controller = new AbortController()
    getNextEventId(controller.signal)
      .then(({ eventId }) => {
        if (!controller.signal.aborted) {
          setValues((current) => ({ ...current, eventId }))
          setIdentifier({ revision: idRevision, error: null })
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setIdentifier({ revision: idRevision, error: 'Não foi possível consultar o próximo identificador.' })
        }
      })

    return () => controller.abort()
  }, [idRevision])

  function updateField<K extends Exclude<keyof FormValues, 'eventId'>>(
    field: K,
    value: FormValues[K],
  ) {
    setValues((current) => ({
      ...current,
      [field]: value,
    }))

    setError(null)
    setSuccess(null)
  }

  async function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()

    if (isSubmitting || idLoading || idError) {
      return
    }

    setError(null)
    setSuccess(null)

    const eventId = values.eventId.trim()
    const description = values.description.trim()
    const occurredAt = new Date(values.timestamp)

    if (!eventId || !description || !values.timestamp) {
      setError('Preencha todos os campos obrigatórios.')
      return
    }

    if (Number.isNaN(occurredAt.getTime())) {
      setError('Informe uma data e hora válidas.')
      return
    }

    setIsSubmitting(true)

    try {
      const created = await createManualEvent({
        requestId,
        eventType: values.eventType,
        description,
        timestamp: occurredAt.toISOString(),
      })
      setSuccess(`Evento ${created.eventId} cadastrado com sucesso.`)
    } catch (cause: unknown) {
      if (cause instanceof ApiError) {
        setError(cause.message)
        setRequestConflict(cause.problem?.code === 'MANUAL_REQUEST_CONFLICT')
      } else {
        setError(
          'Não foi possível comunicar com a API. Verifique a conexão e tente novamente.',
        )
      }

      return
    } finally {
      setIsSubmitting(false)
    }

    setValues(createInitialValues())
    setRequestId(crypto.randomUUID())
    setIdRevision((current) => current + 1)

    onCreated?.()
  }

  return (
    <section className="event-card" aria-labelledby="event-form-title">
      <header className="card-heading">
        <div>
          <h2 id="event-form-title">Novo evento</h2>
          <p>Preencha os dados da ocorrência para registrar.</p>
        </div>
      </header>

      <form className="event-form" onSubmit={handleSubmit} aria-busy={isSubmitting}>
        <fieldset className="form-grid" disabled={isSubmitting}>
          <legend className="sr-only">Dados do evento — todos os campos são obrigatórios</legend>

          <div className="form-field">
            <label htmlFor="eventId">Identificador <span className="field-required" aria-hidden="true">*</span></label>

            <input
              id="eventId"
              name="eventId"
              type="text"
              value={values.eventId}
              placeholder={idLoading ? 'Consultando…' : 'Indisponível'}
              readOnly
              aria-describedby="event-id-help"
            />

            <small id="event-id-help">
              Gerado no cadastro. Em acessos simultâneos, o código confirmado pode mudar.
            </small>
          </div>

          <div className="form-field">
            <label htmlFor="eventType">Tipo do evento <span className="field-required" aria-hidden="true">*</span></label>

            <select
              id="eventType"
              name="eventType"
              value={values.eventType}
              onChange={(event) => {
                const value = event.target.value

                if (value === 'PRODUCTION' || value === 'QUALITY') {
                  updateField('eventType', value)
                }
              }}
              required
              aria-describedby="event-type-help"
            >
              <option value="PRODUCTION">Produção</option>
              <option value="QUALITY">Qualidade</option>
            </select>
            <small id="event-type-help">
              {values.eventType === 'QUALITY'
                ? 'Gera um registro de auditoria automaticamente.'
                : 'Registra uma ocorrência da operação.'}
            </small>
          </div>

          <div className="form-field form-field--full">
            <label htmlFor="description">Descrição <span className="field-required" aria-hidden="true">*</span></label>

            <textarea
              id="description"
              name="description"
              value={values.description}
              onChange={(event) =>
                updateField('description', event.target.value)
              }
              required
              maxLength={2000}
              rows={4}
              placeholder="Ex.: linha de produção iniciada após manutenção preventiva."
              aria-describedby="description-help"
            />
            <div className="field-caption" id="description-help">
              <small>Conte o que aconteceu de forma objetiva.</small>
              <small>{values.description.length}/2000</small>
            </div>
          </div>

          <div className="form-field form-field--full">
            <label htmlFor="timestamp">Data e hora da ocorrência <span className="field-required" aria-hidden="true">*</span></label>

            <input
              id="timestamp"
              name="timestamp"
              type="datetime-local"
              value={values.timestamp}
              onChange={(event) =>
                updateField('timestamp', event.target.value)
              }
              required
              step={1}
              aria-describedby="timestamp-help"
            />

            <small id="timestamp-help">
              Use o horário local configurado no seu navegador.
            </small>
          </div>

          <div className="form-actions form-field--full">
            <span className="required-note">* Campos obrigatórios</span>
            <button className="primary-button" type="submit" disabled={idLoading || !!idError || !values.eventId}>
              {isSubmitting ? 'Registrando…' : 'Registrar evento'}
              <span aria-hidden="true">{isSubmitting ? '…' : '→'}</span>
            </button>
          </div>
        </fieldset>

        {idError && (
          <div className="feedback feedback--error" role="alert">
            <p>{idError}</p>
            <button type="button" className="text-button" onClick={() => setIdRevision((current) => current + 1)}>
              Tentar novamente
            </button>
          </div>
        )}

        {error && (
          <div className="feedback feedback--error" role="alert">
            <span className="feedback-symbol" aria-hidden="true">!</span>
            <p>{error}</p>
            {requestConflict && (
              <button className="text-button" type="button" onClick={() => {
                setRequestId(crypto.randomUUID())
                setRequestConflict(false)
                setError(null)
                setIdRevision((current) => current + 1)
              }}>Iniciar novo cadastro</button>
            )}
          </div>
        )}

        {success && (
          <div className="feedback feedback--success" role="status">
            <span className="feedback-symbol" aria-hidden="true">✓</span>
            <p>{success}</p>
          </div>
        )}
      </form>
    </section>
  )
}
