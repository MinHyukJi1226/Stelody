import { expect, type Page } from '@playwright/test'

export async function changeTheme(page: Page, name: string | RegExp) {
  await page.getByRole('button', { name: '메뉴 열기', exact: true }).click()
  await page
    .getByRole('button', { name, exact: typeof name === 'string' })
    .click()
  await page.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(
    page.getByRole('dialog', { name: '메뉴', exact: true }),
  ).toHaveCount(0)
}
