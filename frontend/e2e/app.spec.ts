import { expect, test } from '@playwright/test'

test('직접 접근한 주소와 알 수 없는 경로에서 홈으로 복귀한다', async ({
  page,
}) => {
  await page.goto('/')
  await expect(
    page.getByRole('heading', { name: 'Stelody', exact: true }),
  ).toBeVisible()
  await page.reload()
  await expect(
    page.getByRole('heading', { name: 'Stelody', exact: true }),
  ).toBeVisible()
  await page.goto('/unknown-page')
  await expect(
    page.getByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
  ).toBeVisible()
  await page.getByRole('link', { name: '홈으로 돌아가기' }).focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL('/')
  await expect(
    page.getByRole('heading', { name: 'Stelody', exact: true }),
  ).toBeVisible()
})
