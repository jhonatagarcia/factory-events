export type EventType = 'PRODUCTION' | 'QUALITY'

export interface CreateEventRequest {
  eventId: string
  eventType: EventType
  description: string
  timestamp: string
}

export interface EventResponse {
  eventId: string
  eventType: EventType
  description: string
  timestamp: string
  receivedAt: string
}

export interface EventPageResponse {
  items: EventResponse[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export interface CreateManualEventRequest {
  requestId: string
  eventType: EventType
  description: string
  timestamp: string
}
