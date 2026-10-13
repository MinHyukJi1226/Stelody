import { readFile } from 'node:fs/promises'
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript'
import prettier from 'prettier'

const config = await prettier.resolveConfig('.prettierrc.json')
const schema = JSON.parse(await readFile('openapi/stelody.json', 'utf8'))
const expected = await prettier.format(
  COMMENT_HEADER + astToString(await openapiTS(schema)),
  {
    ...config,
    parser: 'typescript',
  },
)
const actual = await readFile('src/shared/api/generated/schema.d.ts', 'utf8')
if (expected !== actual) {
  console.error(
    'OpenAPI 타입이 명세와 다릅니다. npm run api:generate를 실행하세요.',
  )
  process.exitCode = 1
}
