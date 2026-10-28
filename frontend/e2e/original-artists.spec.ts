import { expect, test, type Page } from '@playwright/test'
import { editPath } from './admin-song-fixtures'
import { aid } from '../src/test/admin-song'
import { fixture } from './original-artist-fixtures'
const modal = (page: Page) => page.getByRole('dialog')
async function open(page: Page) {
  await page.goto(editPath)
  await page
    .getByRole('button', { name: '원곡 아티스트 편집', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 편집' }),
  ).toBeVisible()
}
async function clearAndConfirm(page: Page) {
  await modal(page)
    .getByRole('button', { name: '선택 해제', exact: true })
    .click()
  await modal(page)
    .getByLabel('변경 사유*', { exact: true })
    .fill('원곡 크레딧 확인')
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
}
async function pick(page: Page) {
  await modal(page).getByRole('button', { name: '아티스트 찾기·추가' }).click()
  await expect(
    modal(page).getByRole('checkbox', { name: /원곡 아티스트/ }),
  ).toBeVisible()
}
test('원곡만 전체 입력으로 저장하고 곡의 미저장 입력과 원래 목록 문맥을 유지한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await modal(page).getByRole('button', { name: '취소', exact: true }).click()
  await page.getByLabel('곡명*', { exact: true }).fill('아직 저장 안 한 곡명')
  await page.getByLabel('변경 사유*', { exact: true }).fill('곡의 사유')
  await page
    .getByRole('button', { name: '원곡 아티스트 편집', exact: true })
    .click()
  await clearAndConfirm(page)
  await expect(
    modal(page).getByText(
      '아티스트 없음 · 이 원곡의 아티스트 연결을 모두 해제합니다.',
    ),
  ).toBeVisible()
  await expect.poll(() => state.workPuts.length).toBe(0)
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await expect(modal(page)).toHaveCount(0)
  expect(state.workPuts).toEqual([
    {
      version: 8,
      title: '원곡 제목',
      aliases: ['원제'],
      artistIds: [],
      reason: '원곡 크레딧 확인',
      links: state.work.links.map(({ platform, url, sourceUrl }) => ({
        platform,
        url,
        sourceUrl,
      })),
      karaoke: [
        {
          provider: 'TJ',
          status: 'REGISTERED',
          number: '12345',
          sourceUrl: 'https://tjmedia.com/',
        },
      ],
    },
  ])
  expect(state.song.puts).toHaveLength(0)
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '아직 저장 안 한 곡명',
  )
  await expect(page.getByLabel('변경 사유*', { exact: true })).toHaveValue(
    '곡의 사유',
  )
  await expect(page).toHaveURL(new RegExp('returnTo='))
})
test('페이지와 검색을 이동해도 다중 선택을 유지하고 적용만으로 저장하지 않는다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await pick(page)
  await modal(page).getByRole('button', { name: '다음', exact: true }).click()
  await modal(page)
    .getByRole('checkbox', { name: /두 번째 아티스트/ })
    .check()
  await modal(page).getByRole('button', { name: '이전', exact: true }).click()
  await expect(
    modal(page).getByRole('checkbox', { name: /원곡 아티스트/ }),
  ).toBeChecked()
  await expect(
    modal(page).getByText('선택 2명 · 여러 명 선택할 수 있습니다.'),
  ).toBeVisible()
  await modal(page).getByLabel('이름·검색 별칭').fill('없는 이름')
  await modal(page).getByRole('button', { name: '검색', exact: true }).click()
  await expect(modal(page).getByText(/검색 결과가 없습니다/)).toBeVisible()
  await modal(page)
    .getByRole('button', { name: '선택 적용', exact: true })
    .click()
  await expect(
    modal(page).getByRole('button', { name: '두 번째 아티스트 선택 제거' }),
  ).toBeVisible()
  expect(state.workPuts).toHaveLength(0)
  await page.keyboard.press('Escape')
  await expect(
    modal(page).getByRole('heading', { name: '편집을 그만둘까요?' }),
  ).toBeVisible()
  await modal(page).getByRole('button', { name: '계속 편집' }).click()
  await expect(
    modal(page).getByRole('button', { name: '두 번째 아티스트 선택 제거' }),
  ).toBeVisible()
})
test('아티스트 이름과 별칭 수정은 공유 확인 후 별도로 저장한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await pick(page)
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 수정', exact: true })
    .click()
  await modal(page).getByLabel('아티스트 이름*').fill('아티스트 수정명')
  await modal(page).getByLabel('검색 별칭 · 선택').fill('새 별칭')
  await modal(page).getByRole('button', { name: '별칭 추가' }).click()
  await modal(page).getByLabel('변경 사유*').fill('이름 정정 근거')
  await modal(page)
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await expect(modal(page).getByText(/외부 참여자 표시에도 변경/)).toBeVisible()
  expect(state.artistWrites).toHaveLength(0)
  await modal(page)
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.artistWrites).toEqual([
    {
      version: 3,
      name: '아티스트 수정명',
      aliases: ['한국어 발음', '새 별칭'],
      reason: '이름 정정 근거',
    },
  ])
  expect(state.workPuts).toHaveLength(0)
  expect(state.song.puts).toHaveLength(0)
})
test('새 아티스트를 등록한 뒤 선택에 돌아오며 원곡 저장은 따로 한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await pick(page)
  await modal(page).getByRole('button', { name: '새 아티스트 등록' }).click()
  await modal(page).getByLabel('아티스트 이름*').fill('새 아티스트')
  await modal(page).getByLabel('변경 사유*').fill('크레딧 확인 후 등록')
  await modal(page)
    .getByRole('button', { name: '아티스트 등록', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  await expect(modal(page).getByText(/아직 이 원곡에는 저장되지/)).toBeVisible()
  await modal(page).getByRole('button', { name: '선택 적용' }).click()
  await expect(
    modal(page).getByRole('button', { name: '새 아티스트 선택 제거' }),
  ).toBeVisible()
  expect(state.artistWrites[0]?.version).toBe(0)
  expect(state.workPuts).toHaveLength(0)
})
test('충돌 시 최초·입력·최신 값을 비교하고 최신 원곡의 다른 정보와 version을 보존한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await clearAndConfirm(page)
  state.work = {
    ...state.work,
    version: 9,
    title: '다른 관리자의 원곡명',
    aliases: ['최신 별칭'],
    links: [],
  }
  state.mode = 'conflict'
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '다른 관리자의 변경이 있어요' }),
  ).toBeVisible()
  await modal(page).getByRole('button', { name: '최신 저장값 조회' }).click()
  await expect(
    modal(page).getByText('최신 저장값', { exact: true }),
  ).toBeVisible()
  expect(state.workPuts).toHaveLength(1)
  await modal(page).getByRole('button', { name: '내 선택을 다시 검토' }).click()
  await expect(
    modal(page).getByText('원곡 · 다른 관리자의 원곡명'),
  ).toBeVisible()
  await expect(modal(page).getByLabel('변경 사유*')).toHaveValue(
    '원곡 크레딧 확인',
  )
  state.mode = 'success'
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await expect(modal(page)).toHaveCount(0)
  expect(state.workPuts[1]).toMatchObject({
    version: 9,
    title: '다른 관리자의 원곡명',
    aliases: ['최신 별칭'],
    links: [],
    artistIds: [],
  })
})
for (const mode of ['lost', 'malformed'] as const)
  test(`원곡 응답 ${mode}는 최신 값이 일치해도 자동 완료·자동 재전송하지 않는다`, async ({
    page,
  }) => {
    const state = await fixture(page)
    await open(page)
    await clearAndConfirm(page)
    state.mode = mode
    await modal(page)
      .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
      .click()
    await expect(
      modal(page).getByRole('heading', { name: '저장 결과를 확인해야 해요' }),
    ).toBeVisible()
    await modal(page).getByRole('button', { name: '최신 저장값 조회' }).click()
    await expect(
      modal(page).getByText(/이 요청의 성공을 증명하는 것은 아닙니다/),
    ).toBeVisible()
    expect(state.workPuts).toHaveLength(1)
    await modal(page).getByRole('button', { name: '편집으로 돌아가기' }).click()
    await expect(modal(page).getByLabel('변경 사유*')).toHaveValue(
      '원곡 크레딧 확인',
    )
    await page.keyboard.press('Escape')
    await expect(
      modal(page).getByRole('heading', {
        name: '저장 결과를 확인하지 않고 나갈까요?',
      }),
    ).toBeVisible()
  })
test('입력 오류에서는 원곡 선택과 사유를 보존한다', async ({ page }) => {
  const state = await fixture(page)
  await open(page)
  await clearAndConfirm(page)
  state.mode = 'validation'
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await expect(modal(page).getByText('근거를 더 입력해주세요')).toBeVisible()
  await expect(modal(page).getByLabel('변경 사유*')).toHaveValue(
    '원곡 크레딧 확인',
  )
  await expect(
    modal(page).getByText('아티스트 없음', { exact: true }),
  ).toBeVisible()
})
test('원곡 조회 실패·없음은 재조회와 곡 입력 복귀로 처리한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await page.goto(editPath)
  await expect(page.getByLabel('곡명*', { exact: true })).toBeVisible()
  state.readError = 503
  await page
    .getByRole('button', { name: '원곡 아티스트 편집', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 정보를 조회하지 못했어요' }),
  ).toBeVisible()
  state.readError = 404
  await modal(page).getByRole('button', { name: '다시 조회' }).click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡을 확인할 수 없어요' }),
  ).toBeVisible()
  await modal(page)
    .getByRole('button', { name: '곡 수정으로 돌아가기' })
    .click()
  await expect(page.getByLabel('곡명*', { exact: true })).toHaveValue(
    '노래 제목',
  )
})
test('PC·모바일 라이트·다크에서 긴 이름과 별칭이 화면을 넘지 않는다', async ({
  page,
}, info) => {
  const state = await fixture(page)
  state.artists[aid]!.name = '긴아티스트이름'.repeat(20)
  state.artists[aid]!.aliases = ['긴별칭'.repeat(50)]
  await open(page)
  for (const theme of ['light', 'dark']) {
    await page.evaluate((theme) => {
      document.documentElement.dataset.theme = theme
      document.documentElement.classList.toggle('dark', theme === 'dark')
    }, theme)
    await expect(modal(page)).toBeVisible()
    const box = await modal(page).boundingBox(),
      viewport = page.viewportSize()!
    expect(box!.width).toBeLessThanOrEqual(viewport.width - 30)
    expect(box!.height).toBeLessThanOrEqual(viewport.height - 30)
    expect(
      await modal(page).evaluate((e) => e.scrollWidth <= e.clientWidth + 1),
    ).toBe(true)
    await page.screenshot({
      path: `test-results/work-artists-${info.project.name}-${theme}.png`,
      fullPage: false,
    })
  }
})

test('권한 확인 오류 후 복구해도 원곡과 아티스트 입력은 보존한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await modal(page).getByLabel('변경 사유*').fill('원곡 사유 보존')
  await pick(page)
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 수정', exact: true })
    .click()
  await modal(page).getByLabel('아티스트 이름*').fill('수정 중 이름')
  await modal(page).getByLabel('검색 별칭 · 선택').fill('입력 중 별칭')
  state.song.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  await expect(modal(page)).toHaveCount(0)
  state.song.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(modal(page).getByLabel('아티스트 이름*')).toHaveValue(
    '수정 중 이름',
  )
  await expect(modal(page).getByLabel('검색 별칭 · 선택')).toHaveValue(
    '입력 중 별칭',
  )
  expect(state.workPuts).toHaveLength(0)
  expect(state.artistWrites).toHaveLength(0)
})
test('CSRF 대기 중 권한 재확인이 실패하면 PUT 전송을 중단하고 결과 미확정으로 보존한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await clearAndConfirm(page)
  let release!: () => void,
    started = false
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/auth/csrf', async (route) => {
    started = true
    await gate
    return route.fulfill({
      json: { headerName: 'X-CSRF-TOKEN', token: 'test' },
    })
  })
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await expect.poll(() => started).toBe(true)
  state.song.admin.sessionError = 503
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(
    page.getByRole('heading', { name: '권한을 확인하지 못했어요' }),
  ).toBeVisible()
  const response = page.waitForResponse('**/api/v1/auth/csrf')
  release()
  await response
  state.song.admin.sessionError = 0
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(
    modal(page).getByRole('heading', { name: '저장 결과를 확인해야 해요' }),
  ).toBeVisible()
  expect(state.workPuts).toHaveLength(0)
  await expect(
    page.getByText('원곡 아티스트를 저장했습니다.', { exact: true }),
  ).toHaveCount(0)
})
test('원곡 조회 403은 공통 권한 없음 화면으로 전환한다', async ({ page }) => {
  const state = await fixture(page)
  await page.goto(editPath)
  await expect(page.getByLabel('곡명*', { exact: true })).toBeVisible()
  state.readError = 403
  await page
    .getByRole('button', { name: '원곡 아티스트 편집', exact: true })
    .click()
  await expect(
    page.getByRole('heading', { name: '관리자 권한이 없어요' }),
  ).toBeVisible()
  await expect(modal(page)).toHaveCount(0)
  expect(state.workPuts).toHaveLength(0)
})
test('저장 중 중복 실행과 취소를 막는다', async ({ page }) => {
  const state = await fixture(page)
  await open(page)
  await clearAndConfirm(page)
  let release!: () => void
  state.saveGate = new Promise<void>((resolve) => {
    release = resolve
  })
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 저장', exact: true })
    .click()
  await expect.poll(() => state.workPuts.length).toBe(1)
  await expect(
    modal(page).getByRole('button', { name: '저장 중…' }),
  ).toBeDisabled()
  await expect(
    modal(page).getByRole('button', { name: '취소', exact: true }),
  ).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(modal(page)).toBeVisible()
  release()
  await expect(modal(page)).toHaveCount(0)
  expect(state.workPuts).toHaveLength(1)
})
test('새 아티스트 등록 응답 유실은 요청 결과의 ID로 복구하고 다시 등록하지 않는다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await pick(page)
  await modal(page).getByRole('button', { name: '새 아티스트 등록' }).click()
  await modal(page).getByLabel('아티스트 이름*').fill('생성 결과 확인 필요')
  await modal(page).getByLabel('변경 사유*').fill('테스트 등록')
  state.mode = 'lost'
  await modal(page)
    .getByRole('button', { name: '아티스트 등록', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.artistWrites).toHaveLength(1)
  expect(state.lookupCalls).toContain(state.registrations[0]!.key)
  await modal(page).getByRole('button', { name: '선택 적용' }).click()
  await expect(
    modal(page).getByRole('button', { name: '생성 결과 확인 필요 선택 제거' }),
  ).toBeVisible()
  expect(state.workPuts).toHaveLength(0)
})
test('아티스트 충돌에도 이름·별칭 초안을 유지하고 최신 version으로만 명시적으로 다시 저장한다', async ({
  page,
}) => {
  const state = await fixture(page)
  await open(page)
  await pick(page)
  await modal(page)
    .getByRole('button', { name: '원곡 아티스트 수정', exact: true })
    .click()
  await modal(page).getByLabel('아티스트 이름*').fill('내 수정명')
  await modal(page).getByLabel('변경 사유*').fill('정정 사유')
  await modal(page)
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  state.mode = 'conflict'
  state.artists[aid] = {
    ...state.artists[aid]!,
    version: 4,
    name: '다른 관리자 이름',
  }
  await modal(page)
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '다른 관리자의 변경이 있어요' }),
  ).toBeVisible()
  await modal(page).getByRole('button', { name: '최신 저장값 조회' }).click()
  await expect(
    modal(page).getByText('다른 관리자 이름', { exact: true }),
  ).toBeVisible()
  await modal(page).getByRole('button', { name: '내 선택을 다시 검토' }).click()
  await expect(modal(page).getByLabel('아티스트 이름*')).toHaveValue(
    '내 수정명',
  )
  state.mode = 'success'
  await modal(page)
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await modal(page)
    .getByRole('button', { name: '아티스트 정보 저장', exact: true })
    .click()
  await expect(
    modal(page).getByRole('heading', { name: '원곡 아티스트 선택' }),
  ).toBeVisible()
  expect(state.artistWrites[1]?.version).toBe(4)
  await modal(page).getByRole('button', { name: '취소', exact: true }).click()
  await expect(
    modal(page).getByRole('button', { name: '내 수정명 선택 제거' }),
  ).toBeVisible()
})

test('원곡·검색·아티스트 수정창의 정상 디자인을 양쪽 테마에서 확인한다', async ({
  page,
}, info) => {
  await fixture(page)
  await open(page)
  for (const theme of ['light', 'dark']) {
    await page.evaluate((value) => {
      document.documentElement.dataset.theme = value
    }, theme)
    await expect(
      modal(page).getByRole('heading', { name: '원곡 아티스트 편집' }),
    ).toBeVisible()
    await page.screenshot({
      path: `test-results/original-normal-${info.project.name}-${theme}.png`,
    })
    await pick(page)
    await page.screenshot({
      path: `test-results/original-picker-${info.project.name}-${theme}.png`,
    })
    await modal(page)
      .getByRole('button', { name: '원곡 아티스트 수정', exact: true })
      .click()
    await expect(
      modal(page).getByText('이 아티스트를 쓰는 곳에 함께 반영돼요'),
    ).toBeVisible()
    await page.screenshot({
      path: `test-results/original-form-${info.project.name}-${theme}.png`,
    })
    await modal(page).getByRole('button', { name: '취소', exact: true }).click()
    await modal(page).getByRole('button', { name: '취소', exact: true }).click()
  }
})
