export interface FieldError {
  field: string
  message: string
}

export interface ApiProblem {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  errors?: FieldError[]
}