import { useState } from 'react'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { FieldError } from './song-ui'

export type ArtistFormValue = {
  name: string
  aliases: string[]
  alias: string
  reason: string
}
export function ArtistFields({
  value,
  change,
  errors,
  busy,
  dirty,
  action,
  save,
  cancel,
}: {
  value: ArtistFormValue
  change: (patch: Partial<ArtistFormValue>) => void
  errors: Record<string, string>
  busy: boolean
  dirty: boolean
  action: string
  save: () => void
  cancel: () => void
}) {
  const [aliasError, setAliasError] = useState('')
  function addAlias() {
    const alias = value.alias.trim()
    if (!alias) return
    const normalize = (v: string) =>
      v
        .normalize('NFKC')
        .replace(/[\s\p{Z}]+/gu, ' ')
        .trim()
        .toLowerCase()
    if (
      value.aliases.length >= 50 ||
      value.aliases.some((x) => normalize(x) === normalize(alias))
    ) {
      setAliasError(
        value.aliases.length >= 50
          ? '별칭은 최대 50개입니다.'
          : '공백·대소문자가 같은 별칭이 있습니다.',
      )
      return
    }
    change({ aliases: [...value.aliases, alias], alias: '' })
    setAliasError('')
  }
  return (
    <fieldset className="grid gap-6" disabled={busy} aria-busy={busy}>
      <TextField
        label="아티스트 이름*"
        value={value.name}
        maxLength={200}
        onChange={(e) => change({ name: e.target.value })}
        error={errors.name ?? ''}
        description="최대 200자"
      />
      <div className="grid gap-2">
        <TextField
          label="검색 별칭 · 선택"
          value={value.alias}
          maxLength={200}
          onChange={(e) => {
            change({ alias: e.target.value })
            setAliasError('')
          }}
          error={aliasError || errors.aliases || ''}
          description="최대 50개 · 각 200자"
        />
        <div className="flex flex-wrap items-start gap-2">
          <Button
            disabled={!value.alias.trim() || value.aliases.length >= 50}
            onClick={addAlias}
          >
            별칭 추가
          </Button>
          {value.aliases.map((x, i) => (
            <div key={i}>
              <Button
                aria-label={`${x} 별칭 삭제`}
                onClick={() =>
                  change({ aliases: value.aliases.filter((_, j) => i !== j) })
                }
              >
                {x} ×
              </Button>
              <FieldError message={errors[`aliases[${i}]`]} />
            </div>
          ))}
        </div>
      </div>
      <TextField
        label="변경 사유*"
        value={value.reason}
        maxLength={500}
        onChange={(e) => change({ reason: e.target.value })}
        error={errors.reason ?? ''}
        description="최대 500자"
      />
      {Object.entries(errors)
        .filter(
          ([k]) =>
            !['name', 'aliases', 'reason'].includes(k) &&
            !k.startsWith('aliases['),
        )
        .map(([k, v]) => (
          <FieldError key={k} message={v} />
        ))}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="primary"
          disabled={!dirty || !!value.alias.trim()}
          onClick={save}
        >
          {busy ? '저장 중…' : action}
        </Button>
        <Button onClick={cancel}>취소</Button>
      </div>
    </fieldset>
  )
}
