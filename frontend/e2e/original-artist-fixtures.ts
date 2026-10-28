import type { Page } from '@playwright/test'
import { mockSongEditor } from './admin-song-fixtures'
import { aid, wid } from '../src/test/admin-song'
import { makeSong } from './fixtures'
import type {
  Artist,
  ArtistInput,
  ArtistRegistrationResult,
  Work,
  WorkInput,
} from '../src/features/admin/original-artists'
export const bid = makeSong(831).id,
  cid = makeSong(832).id
export async function fixture(page: Page) {
  const song = await mockSongEditor(page)
  const state = {
    song,
    work: {
      id: wid,
      version: 8,
      title: '원곡 제목',
      aliases: ['원제'],
      artistIds: [aid],
      links: song.song.links,
      karaoke: song.song.karaoke,
    } as Work,
    artists: {
      [aid]: {
        id: aid,
        version: 3,
        name: '원곡 아티스트',
        aliases: ['한국어 발음'],
      },
      [bid]: { id: bid, version: 2, name: '두 번째 아티스트', aliases: [] },
    } as Record<string, Artist>,
    workPuts: [] as WorkInput[],
    artistWrites: [] as ArtistInput[],
    registrations: [] as { key: string; body: ArtistInput }[],
    receipts: new Map<
      string,
      { body: ArtistInput; status: ArtistRegistrationResult['status'] }
    >(),
    lookupCalls: [] as string[],
    lookupError: 0,
    lookupMalformed: false,
    createBehavior: 'normal' as
      'normal' | 'notReceived' | 'processing' | 'failed' | 'conflict',

    mode: 'success' as
      'success' | 'lost' | 'conflict' | 'validation' | 'malformed',
    readError: 0,
    pageError: false,
    saveGate: Promise.resolve(),
    reads: 0,
  }
  await page.route('**/api/v1/admin/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname.replace('/api/v1/admin/', '')
    if (path === `works/${wid}`) {
      if (request.method() === 'GET') {
        state.reads++
        return route.fulfill(
          state.readError
            ? {
                status: state.readError,
                json: {
                  code:
                    state.readError === 403
                      ? 'ACCESS_DENIED'
                      : 'CATALOG_RESOURCE_NOT_FOUND',
                },
              }
            : { json: state.work },
        )
      }
      const body = request.postDataJSON() as WorkInput
      state.workPuts.push(body)
      await state.saveGate
      if (state.mode === 'conflict')
        return route.fulfill({
          status: 409,
          json: { code: 'CATALOG_VERSION_CONFLICT' },
        })
      if (state.mode === 'validation')
        return route.fulfill({
          status: 400,
          json: {
            code: 'INVALID_CATALOG_REQUEST',
            fieldErrors: [
              {
                field: 'reason',
                code: 'INVALID_VALUE',
                message: '근거를 더 입력해주세요',
              },
            ],
          },
        })
      state.work = {
        ...state.work,
        ...body,
        version: body.version + 1,
        links: state.work.links,
        karaoke: state.work.karaoke,
      }
      if (state.mode === 'lost') return route.abort('failed')
      return route.fulfill({
        json:
          state.mode === 'malformed'
            ? { item: state.work }
            : { item: state.work, possibleDuplicateIds: [] },
      })
    }
    if (path.startsWith('artist-registrations/')) {
      const key = path.split('/')[1]!
      state.lookupCalls.push(key)
      if (state.lookupError)
        return route.fulfill({
          status: state.lookupError,
          json: {
            code:
              state.lookupError === 410
                ? 'ARTIST_REGISTRATION_RESULT_EXPIRED'
                : state.lookupError === 403
                  ? 'ACCESS_DENIED'
                  : state.lookupError === 401
                    ? 'AUTHENTICATION_REQUIRED'
                    : 'ARTIST_REGISTRATION_STORAGE_UNAVAILABLE',
          },
        })
      if (state.lookupMalformed)
        return route.fulfill({
          json: { requestId: key, status: 'COMPLETED', artistId: cid },
        })
      const receipt = state.receipts.get(key)
      const status = receipt?.status ?? 'NOT_PROCESSED'
      const result: ArtistRegistrationResult = {
        requestId: key,
        status,
        artistId: status === 'COMPLETED' ? cid : null,
        errorCode:
          status === 'FAILED' ? 'ARTIST_REGISTRATION_INTERRUPTED' : null,
        createdAt: receipt ? '2026-10-28T00:00:00Z' : null,
        expiresAt: receipt ? '2026-10-29T00:00:00Z' : null,
        finishedAt:
          status === 'FAILED' || status === 'COMPLETED'
            ? '2026-10-28T00:00:03Z'
            : null,
        pollAfterSeconds: status === 'PROCESSING' ? 3 : null,
      }
      return route.fulfill({ json: result })
    }
    if (path === 'artists' && request.method() === 'GET') {
      const pageNo = Number(url.searchParams.get('page')),
        q = url.searchParams.get('q') ?? ''
      return route.fulfill(
        state.pageError
          ? { status: 503, json: { code: 'UNAVAILABLE' } }
          : {
              json: {
                page: pageNo,
                size: 20,
                hasNext: pageNo === 0 && !q,
                items: Object.values(state.artists)
                  .filter(
                    (x) => !q || (x.name + x.aliases.join(' ')).includes(q),
                  )
                  .filter((_, i) => q || i === pageNo)
                  .map((x) => ({
                    id: x.id,
                    name: x.name,
                    version: x.version,
                    status: null,
                  })),
              },
            },
      )
    }
    if (path === 'artists' || path.startsWith('artists/')) {
      const id = path.split('/')[1] ?? cid
      if (request.method() === 'GET')
        return route.fulfill({ json: state.artists[id] })
      const body = request.postDataJSON() as ArtistInput
      state.artistWrites.push(body)
      const key = request.headers()['idempotency-key'] ?? ''
      if (request.method() === 'POST') {
        state.registrations.push({ key, body })
        if (!key)
          return route.fulfill({
            status: 400,
            json: { code: 'INVALID_CATALOG_REQUEST' },
          })
        if (state.createBehavior === 'notReceived') return route.abort('failed')
        if (state.createBehavior === 'conflict')
          return route.fulfill({
            status: 409,
            json: { code: 'ARTIST_REGISTRATION_REQUEST_CONFLICT' },
          })
        if (
          state.createBehavior === 'processing' ||
          state.createBehavior === 'failed'
        ) {
          state.receipts.set(key, {
            body,
            status:
              state.createBehavior === 'processing' ? 'PROCESSING' : 'FAILED',
          })
          return route.fulfill({
            status: state.createBehavior === 'processing' ? 409 : 503,
            json: {
              code:
                state.createBehavior === 'processing'
                  ? 'ARTIST_REGISTRATION_PROCESSING'
                  : 'ARTIST_REGISTRATION_INTERRUPTED',
            },
          })
        }
      }
      await state.saveGate
      if (state.mode === 'conflict')
        return route.fulfill({
          status: 409,
          json: { code: 'CATALOG_VERSION_CONFLICT' },
        })
      if (state.mode === 'validation')
        return route.fulfill({
          status: 400,
          json: {
            code: 'INVALID_CATALOG_REQUEST',
            fieldErrors: [
              {
                field: 'aliases[0]',
                code: 'INVALID_VALUE',
                message: '별칭을 확인해주세요',
              },
            ],
          },
        })
      if (request.method() === 'POST')
        state.receipts.set(key, { body, status: 'COMPLETED' })
      state.artists[id] = { id, ...body, version: body.version + 1 }
      if (state.mode === 'lost') return route.abort('failed')
      return route.fulfill({
        json: { item: state.artists[id], possibleDuplicateIds: [] },
      })
    }
    return route.fallback()
  })
  return state
}
