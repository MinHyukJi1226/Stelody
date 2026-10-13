import { z } from 'zod'

const problemSchema = z.object({
  code: z.string(),
  title: z.string().optional(),
  traceId: z.string().optional(),
  fieldErrors: z
    .array(
      z.object({ field: z.string(), code: z.string(), message: z.string() }),
    )
    .optional(),
})

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | undefined
  readonly fieldErrors: { field: string; code: string; message: string }[]

  constructor(status: number, body: unknown) {
    const parsed = problemSchema.safeParse(body)
    const problem = parsed.success ? parsed.data : undefined
    super(problem?.title ?? '요청을 처리하지 못했습니다. 다시 시도해 주세요.')
    this.name = 'ApiError'
    this.status = status
    this.code = problem?.code ?? 'UNKNOWN_ERROR'
    this.traceId = problem?.traceId
    this.fieldErrors = problem?.fieldErrors ?? []
  }
}

export class ApiResponseError extends Error {
  constructor() {
    super('서버 응답을 확인할 수 없습니다. 다시 시도해 주세요.')
    this.name = 'ApiResponseError'
  }
}
