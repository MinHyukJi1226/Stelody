import { it, expect } from 'vitest'
import {
  validateRules,
  validateSamples,
  type Configuration,
} from './collection-rules'
const empty: Configuration = { cover: [], original: [], exclude: [] }
it('빈 규칙 배열을 허용하고 필수 사유를 검증한다', () => {
  expect(validateRules(empty, '확인')).toEqual({})
  expect(validateRules(empty, '')).toHaveProperty('reason')
})
it('NFKC·대소문자·공백을 정규화한 같은 그룹 중복을 검증한다', () => {
  expect(
    validateRules({
      ...empty,
      cover: [
        { text: 'ＣＯＶＥＲ', match: 'WORD' },
        { text: ' cover ', match: 'PHRASE' },
      ],
    }),
  ).toHaveProperty('configuration.cover[1].text')
})
it('종류별 30개 및 단어·문구 60자 제한을 적용한다', () => {
  expect(
    validateRules({
      ...empty,
      cover: Array.from({ length: 31 }, (_, i) => ({
        text: String(i),
        match: 'WORD' as const,
      })),
    }),
  ).toHaveProperty('configuration.cover')
  expect(
    validateRules({
      ...empty,
      exclude: [{ text: 'a'.repeat(61), match: 'WORD' }],
    }),
  ).toHaveProperty('configuration.exclude[0].text')
})
it('다른 그룹의 같은 표기를 임의로 삭제하지 않는다', () =>
  expect(
    validateRules({
      ...empty,
      cover: [{ text: 'mv', match: 'WORD' }],
      original: [{ text: 'mv', match: 'WORD' }],
    }),
  ).toEqual({}))
it('샘플 1–20개·제목 1–500자를 검증한다', () => {
  expect(validateSamples([])).toHaveProperty('samples')
  expect(
    validateSamples([{ title: ' ', availability: 'PUBLIC' }]),
  ).toHaveProperty('samples[0].title')
  expect(
    validateSamples([{ title: 'a'.repeat(501), availability: 'PRIVATE' }]),
  ).toHaveProperty('samples[0].title')
  expect(validateSamples([{ title: 'cover', availability: 'PUBLIC' }])).toEqual(
    {},
  )
})
