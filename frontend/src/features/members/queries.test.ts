import { expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { createQueryClient } from '@/app/query-client'
import { homeMembersOptions } from './queries'

const id = '00000000-0000-4000-8000-000000000101'
const empty = { items: [], hasNext: false, nextCursor: null, totalCount: 0 }

it('멤버 목록은 졸업 멤버를 포함하고 커서와 서버 순서를 유지한다', async () => {
  const cursors: (string | null)[] = []
  server.use(
    http.get('*/api/v1/members', ({ request }) => {
      const params = new URL(request.url).searchParams
      expect(params.get('status')).toBe('ALL')
      expect(params.get('size')).toBe('50')
      cursors.push(params.get('cursor'))
      return HttpResponse.json(
        params.has('cursor')
          ? empty
          : {
              items: [{ id, name: '멤버' }],
              hasNext: true,
              nextCursor: 'member+/=&cursor',
            },
      )
    }),
  )
  const client = createQueryClient()
  try {
    const result = await client.fetchInfiniteQuery({
      ...homeMembersOptions,
      pages: 3,
    })
    expect(cursors).toEqual([null, 'member+/=&cursor'])
    expect(result.pages[0]?.items[0]?.id).toBe(id)
  } finally {
    client.clear()
  }
})
