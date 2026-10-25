import type { ApiProblem } from '../model/api-error'
import type {
  CreateEventRequest,
  CreateManualEventRequest,
  EventPageResponse,
  EventResponse,
} from '../model/event'

export class ApiError extends Error {
  readonly status: number
  readonly problem?: ApiProblem

  constructor(
    status: number,
    message: string,
    problem?: ApiProblem,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

async function request<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const response = await fetch(path, options)

  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)

    const problem = isObject(body)
      ? (body as ApiProblem)
      : undefined

    const message =
      typeof problem?.detail === 'string'
        ? problem.detail
        : `Não foi possível concluir a operação (${response.status}).`

    throw new ApiError(response.status, message, problem)
  }

  return (await response.json()) as T
}

export function createEvent(
  data: CreateEventRequest,
): Promise<EventResponse> {
  return request<EventResponse>('/api/events', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(data),
  })
}

export function listEvents(
  page = 0,
  size = 20,
  signal?: AbortSignal,
): Promise<EventPageResponse> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  })

  return request<EventPageResponse>(
    `/api/events?${params.toString()}`,
    { signal },
  )
}

export function getNextEventId(signal?: AbortSignal): Promise<{ eventId: string }> {
  return request('/api/events/manual/next-id', { signal })
}

export function createManualEvent(data: CreateManualEventRequest): Promise<EventResponse> {
  return request('/api/events/manual', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
}
