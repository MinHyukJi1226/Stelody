import { test, expect } from './fixtures'

test('프로필 이미지는 같은 주소로 최대 두 번 재요청하고 정상 이미지는 유지한다', async ({
  page,
}) => {
  const requests = { recovering: 0, broken: 0, loaded: 0 }
  const pixel = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
    'base64',
  )
  await page.route('https://profiles.test/*.png', async (route) => {
    const name = new URL(route.request().url()).pathname.slice(1, -4)
    if (!(name in requests)) throw new Error(`알 수 없는 이미지: ${name}`)
    const key = name as keyof typeof requests
    requests[key]++
    if (key === 'broken' || (key === 'recovering' && requests[key] < 3)) {
      await route.fulfill({ status: 503, body: 'Temporarily unavailable' })
    } else {
      await route.fulfill({ contentType: 'image/png', body: pixel })
    }
  })
  await page.route('**/api/v1/members?*', (route) =>
    route.fulfill({
      json: {
        items: Object.keys(requests).map((name, index) => ({
          id: `00000000-0000-4000-8000-00000000000${index}`,
          name,
          kind: 'MEMBER',
          activityStatus: 'ACTIVE',
          generation: 1,
          unitName: '테스트',
          profileImageUrl: `https://profiles.test/${name}.png`,
          chzzkUrl: null,
          xUrl: null,
          songCounts: {
            total: 0,
            originals: 0,
            covers: 0,
            collaborations: 0,
            multipleParticipants: 0,
          },
        })),
        nextCursor: null,
        hasNext: false,
      },
    }),
  )

  await page.goto('/members')
  const recovering = page.getByRole('link', { name: 'recovering', exact: true })
  const broken = page.getByRole('link', { name: 'broken', exact: true })
  const loaded = page.getByRole('link', { name: 'loaded', exact: true })
  await expect
    .poll(() =>
      recovering
        .locator('img')
        .evaluateAll((images) =>
          images.some((image) => (image as HTMLImageElement).naturalWidth > 0),
        ),
    )
    .toBe(true)
  await expect(broken).toContainText('♪')
  await expect(broken.locator('img')).toHaveCount(0)
  await expect(loaded.locator('img')).toBeVisible()
  // Advance beyond both retry delays to detect an unintended retry loop.
  await page.clock.install()
  await page.clock.fastForward(10000)
  expect(requests).toEqual({ recovering: 3, broken: 3, loaded: 1 })
})
