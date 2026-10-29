import { useState } from 'react'
import type { Draft } from '@/features/admin/song-draft'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { TextField } from '@/shared/ui/input'
import { FieldError, Hint, ReadValue, SongCard } from './song-ui'
import type { DraftChange } from './song-editor-fields'
import type { EntityKind } from '@/features/admin/songs'

function CreateChoice<T extends string>({
  label,
  value,
  options,
  onChange,
  error,
}: {
  label: string
  value: T
  options: readonly (readonly [T, string])[]
  onChange: (value: T) => void
  error?: string | undefined
}) {
  const [open, setOpen] = useState(false)
  return (
    <div className="grid gap-2">
      <span className="text-sm font-medium">{label}</span>
      <Button
        className="min-h-14 justify-start border border-line font-normal"
        onClick={() => setOpen(true)}
      >
        {options.find(([v]) => v === value)?.[1]}
      </Button>
      <FieldError message={error} />
      {open && (
        <Dialog labelledBy="create-choice" onDismiss={() => setOpen(false)}>
          <h2 id="create-choice" className="mb-6 text-lg font-bold">
            {label}
          </h2>
          <div className="grid gap-3">
            {options.map(([key, name]) => (
              <Button
                key={key}
                aria-pressed={key === value}
                variant={key === value ? 'primary' : 'secondary'}
                onClick={() => {
                  onChange(key)
                  setOpen(false)
                }}
              >
                {name}
              </Button>
            ))}
            <Button onClick={() => setOpen(false)}>취소</Button>
          </div>
        </Dialog>
      )}
    </div>
  )
}
export function SongCreateFields({
  draft: d,
  change,
  errors,
  alias,
  setAlias,
  addAlias,
  aliasError,
  names,
  labelState,
  select,
  reason,
  setReason,
}: {
  draft: Draft
  change: DraftChange
  errors: Record<string, string>
  alias: string
  setAlias: (value: string) => void
  addAlias: () => void
  aliasError: string
  names: Record<string, string>
  labelState: string
  select: (kind: EntityKind) => void
  reason: string
  setReason: (value: string) => void
}) {
  const named = (ids: string[]) =>
    ids.length ? ids.map((id) => names[id] ?? labelState).join(' · ') : '없음'
  return (
    <>
      <div className="song-create-grid">
        <TextField
          label="곡명*"
          value={d.title}
          maxLength={300}
          onChange={(e) => change('title', e.target.value)}
          error={errors.title ?? ''}
          description="최대 300자"
        />
        <CreateChoice
          label="곡 유형*"
          value={d.type}
          options={[
            ['COVER', '커버'],
            ['ORIGINAL', '오리지널'],
          ]}
          onChange={(value) => change('type', value)}
          error={errors.type}
        />
        <div className="grid gap-3">
          <TextField
            label="검색 별칭 · 선택"
            value={alias}
            maxLength={300}
            onChange={(e) => setAlias(e.target.value)}
            error={aliasError || errors.aliases || ''}
            description="최대 50개 · 각 300자"
          />
          <div className="flex flex-wrap gap-2">
            {d.aliases.map((s, i) => (
              <Button
                key={s}
                aria-label={`별칭 ${s} 삭제`}
                onClick={() =>
                  change(
                    'aliases',
                    d.aliases.filter((_, j) => i !== j),
                  )
                }
              >
                {s} ×
              </Button>
            ))}
          </div>
          {d.aliases.map((s, i) => (
            <FieldError key={s} message={errors[`aliases[${i}]`]} />
          ))}
          <Button
            disabled={!alias.trim() || d.aliases.length >= 50}
            onClick={addAlias}
          >
            별칭 추가
          </Button>
        </div>
        <div className="song-create-relation grid content-start gap-3">
          <ReadValue label="참여 멤버">{named(d.memberIds)}</ReadValue>
          <FieldError message={errors.memberIds} />
        </div>
        <div className="song-create-relation grid gap-3">
          <ReadValue label="외부 아티스트">
            {named(d.externalArtistIds)}
          </ReadValue>
          <FieldError message={errors.externalArtistIds} />
        </div>
        <div className="song-create-relation grid gap-3">
          <ReadValue label="원곡">
            {d.workId ? (names[d.workId] ?? labelState) : '선택하지 않음'}
          </ReadValue>
          <FieldError message={errors.workId} />
        </div>
        <ReadValue label="공개 설정">초안</ReadValue>
        <ReadValue label="대표 영상">아직 연결된 영상 없음</ReadValue>
        <CreateChoice
          label="특별 목적"
          value={d.isSpecialEvent ? 'yes' : 'no'}
          options={[
            ['no', '사용하지 않음'],
            ['yes', '사용'],
          ]}
          onChange={(value) => change('isSpecialEvent', value === 'yes')}
          error={errors.isSpecialEvent}
        />
        <CreateChoice
          label="검색 확인"
          value={d.searchVisibility}
          options={[
            ['UNCHECKED', '미확인'],
            ['NORMAL', '검색 가능'],
            ['DIFFICULT', '검색 어려움'],
          ]}
          onChange={(value) => change('searchVisibility', value)}
          error={errors.searchVisibility}
        />
        {d.isSpecialEvent && (
          <TextField
            label="특별 목적 표시 문구*"
            maxLength={60}
            value={d.specialEventLabel}
            onChange={(e) => change('specialEventLabel', e.target.value)}
            error={errors.specialEventLabel ?? ''}
          />
        )}
        <TextField
          label={
            d.searchVisibility === 'DIFFICULT'
              ? '권장 검색어*'
              : '권장 검색어 · 선택'
          }
          value={d.recommendedSearchQuery}
          disabled={d.searchVisibility === 'UNCHECKED'}
          maxLength={300}
          onChange={(e) => change('recommendedSearchQuery', e.target.value)}
          error={errors.recommendedSearchQuery ?? ''}
        />
        <ReadValue label="링크">
          {d.links.length ? `${d.links.length}개 입력` : '등록하지 않음'}
        </ReadValue>
        <ReadValue label="곡별 노래방 확인">
          {d.karaoke
            .map(
              (k) =>
                `${k.provider} · ${k.status === 'UNKNOWN' ? '미확인' : k.status === 'REGISTERED' ? '등록 확인' : '미등록 확인'}`,
            )
            .join(' / ')}
        </ReadValue>
        <TextField
          label="등록 사유*"
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
          error={errors.reason ?? ''}
          description="모든 등록에서 필수 · 최대 500자"
        />
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" onClick={() => select('members')}>
          멤버 선택
        </Button>
        <Button onClick={() => select('artists')}>외부 아티스트 선택</Button>
        <Button onClick={() => select('works')}>원곡 선택</Button>
      </div>
      <Button
        variant="primary"
        className="justify-self-start"
        disabled={d.links.length >= 20}
        onClick={() =>
          change('links', [
            ...d.links,
            { platform: '', url: '', sourceUrl: '' },
          ])
        }
      >
        링크 추가
      </Button>
      <FieldError message={errors.links} />
      {d.links.length > 0 && (
        <SongCard title="곡 링크">
          {d.links.map((link, i) => (
            <div key={i} className="grid gap-4">
              <h3 className="font-bold">링크 {i + 1}</h3>
              <div className="song-create-grid">
                {(
                  [
                    ['platform', '플랫폼*', 60],
                    ['url', '링크 URL*', 2000],
                    ['sourceUrl', '근거 URL*', 2000],
                  ] as const
                ).map(([key, label, maxLength]) => (
                  <TextField
                    key={key}
                    label={label}
                    maxLength={maxLength}
                    value={link[key]}
                    error={errors[`links[${i}].${key}`] ?? ''}
                    onChange={(e) =>
                      change(
                        'links',
                        d.links.map((x, j) =>
                          j === i ? { ...x, [key]: e.target.value } : x,
                        ),
                      )
                    }
                  />
                ))}
              </div>
              <Button
                onClick={() =>
                  change(
                    'links',
                    d.links.filter((_, j) => i !== j),
                  )
                }
              >
                링크 {i + 1} 삭제
              </Button>
            </div>
          ))}
          <Hint>최대 20개 · 곡 링크와 근거를 함께 입력합니다.</Hint>
        </SongCard>
      )}
      <SongCard title="곡별 노래방 정보">
        {d.karaoke.map((k, i) => (
          <div className="grid gap-4" key={k.provider}>
            <CreateChoice
              label={`${k.provider} 상태`}
              value={k.status}
              options={[
                ['UNKNOWN', '미확인'],
                ['NOT_LISTED', '미등록 확인'],
                ['REGISTERED', '등록 확인'],
              ]}
              onChange={(value) =>
                change(
                  'karaoke',
                  d.karaoke.map((x, j) =>
                    j === i ? { ...x, status: value } : x,
                  ),
                )
              }
              error={errors[`karaoke[${i}].status`]}
            />
            <TextField
              label={`${k.provider} 번호${k.status === 'REGISTERED' ? '*' : ''}`}
              disabled={k.status !== 'REGISTERED'}
              value={k.number}
              maxLength={40}
              error={errors[`karaoke[${i}].number`] ?? ''}
              onChange={(e) =>
                change(
                  'karaoke',
                  d.karaoke.map((x, j) =>
                    j === i ? { ...x, number: e.target.value } : x,
                  ),
                )
              }
            />
            <TextField
              label={`${k.provider} 근거 URL${k.status === 'UNKNOWN' ? ' · 선택' : '*'}`}
              disabled={k.status === 'UNKNOWN'}
              value={k.sourceUrl}
              maxLength={2000}
              error={errors[`karaoke[${i}].sourceUrl`] ?? ''}
              onChange={(e) =>
                change(
                  'karaoke',
                  d.karaoke.map((x, j) =>
                    j === i ? { ...x, sourceUrl: e.target.value } : x,
                  ),
                )
              }
            />
            <Hint>확인 시각: 서버 관리 값</Hint>
          </div>
        ))}
        <FieldError message={errors.karaoke} />
      </SongCard>
    </>
  )
}
