import { z } from 'zod'

const receiptSchema = z
  .object({
    user: z.uuid(),
    requestId: z.uuid(),
    body: z
      .object({
        version: z.literal(0),
        name: z
          .string()
          .min(1)
          .max(200)
          .refine((value) => !!value.trim()),
        aliases: z.array(z.string().min(1).max(200)).max(50),
        reason: z
          .string()
          .min(1)
          .max(500)
          .refine((value) => !!value.trim()),
      })
      .strict(),
    conflicted: z.boolean(),
  })
  .strict()
export type ArtistReceipt = z.infer<typeof receiptSchema>
export type ArtistRecovery =
  | { state: 'none' | 'unavailable' | 'invalid' }
  | { state: 'pending'; receipt: ArtistReceipt }
const key = (user: string) => `stelody-artist-registration:${user}`
export function readArtistReceipt(user: string): ArtistRecovery {
  let raw: string | null
  try {
    raw = sessionStorage.getItem(key(user))
  } catch {
    return { state: 'unavailable' }
  }
  if (raw === null) return { state: 'none' }
  try {
    const value: unknown = JSON.parse(raw)
    const parsed = receiptSchema.safeParse(value)
    if (!parsed.success || parsed.data.user !== user)
      return { state: 'invalid' }
    // Preserve the original body, including key/array order, for idempotent replay.
    return { state: 'pending', receipt: value as ArtistReceipt }
  } catch {
    return { state: 'invalid' }
  }
}
export function storeArtistReceipt(receipt: ArtistReceipt) {
  if (!receiptSchema.safeParse(receipt).success)
    throw new Error('등록 요청을 보관할 수 없습니다. 입력을 확인해주세요.')
  const raw = JSON.stringify(receipt)
  try {
    sessionStorage.setItem(key(receipt.user), raw)
    if (sessionStorage.getItem(key(receipt.user)) !== raw)
      throw new Error('storage verification failed')
  } catch {
    throw new Error(
      '이 탭에 등록 요청을 보관할 수 없어 전송하지 않았습니다. 브라우저 저장 설정을 확인해주세요.',
    )
  }
}
export function clearArtistReceipt(user: string) {
  try {
    sessionStorage.removeItem(key(user))
  } catch {
    // A retained receipt is queried again; it never triggers automatic creation.
  }
}
