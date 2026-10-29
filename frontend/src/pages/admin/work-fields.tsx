import { useState } from 'react'
import type { Work } from '@/features/admin/original-artists'
import type { WorkDraft } from '@/features/admin/works'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { TextField } from '@/shared/ui/input'
import { FieldError, Hint, ReadValue } from './song-ui'
import { AdminTime } from './admin-shared'

export function WorkFields({
  draft: d,
  change,
  base,
  errors,
  names,
  alias,
  setAlias,
  addAlias,
  reason,
  setReason,
  selectArtists,
}: {
  draft: WorkDraft
  change: <K extends keyof WorkDraft>(key: K, value: WorkDraft[K]) => void
  base: Work | null
  errors: Record<string, string>
  names: Record<string, string>
  alias: string
  setAlias: (value: string) => void
  addAlias: () => void
  reason: string
  setReason: (value: string) => void
  selectArtists: () => void
}) {
  const [status, setStatus] = useState<'TJ' | 'KY' | null>(null)
  return (
    <>
      <div className="song-editor-grid">
        <TextField
          label="원곡 제목*"
          value={d.title}
          maxLength={300}
          onChange={(e) => change('title', e.target.value)}
          error={errors.title ?? ''}
          description="최대 300자"
        />
        <div className="grid gap-3">
          <TextField
            label="검색 별칭 · 선택"
            value={alias}
            maxLength={300}
            onChange={(e) => setAlias(e.target.value)}
            error={errors.aliases ?? ''}
            description="최대 50개 · 각 300자"
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                addAlias()
              }
            }}
          />
          <div className="flex flex-wrap gap-2">
            {d.aliases.map((value, i) => (
              <div key={i} className="grid gap-2">
                <Button
                  aria-label={`별칭 ${value} 삭제`}
                  onClick={() =>
                    change(
                      'aliases',
                      d.aliases.filter((_, j) => j !== i),
                    )
                  }
                >
                  {value} ×
                </Button>
                <FieldError message={errors[`aliases[${i}]`]} />
              </div>
            ))}
            <Button onClick={addAlias} disabled={d.aliases.length >= 50}>
              별칭 추가
            </Button>
          </div>
        </div>
        <div className="grid gap-3">
          <ReadValue label="원곡 아티스트">
            {d.artistIds.length
              ? d.artistIds
                  .map((id) => names[id] ?? '이름 확인 중…')
                  .join(' · ')
              : '없음'}
          </ReadValue>
          <div className="flex flex-wrap gap-2">
            <Button onClick={selectArtists}>아티스트 선택</Button>
            <Button onClick={selectArtists}>아티스트 추가·수정</Button>
          </div>
          <FieldError message={errors.artistIds} />
          <Hint>아티스트 정보와 이 원곡의 연결 정보는 별도로 저장합니다.</Hint>
        </div>
        <div className="grid gap-3">
          <ReadValue label="링크">
            {d.links.length ? `${d.links.length}개 입력` : '등록하지 않음'}
          </ReadValue>
          <Button
            className="justify-self-start"
            onClick={() =>
              change('links', [
                ...d.links,
                { platform: '', url: '', sourceUrl: '' },
              ])
            }
            disabled={d.links.length >= 20}
          >
            링크 추가
          </Button>
          <FieldError message={errors.links} />
        </div>
      </div>
      {d.links.map((x, i) => (
        <div className="grid min-w-0 gap-4" key={i}>
          <h2 className="font-bold">링크 {i + 1}</h2>
          <div className="song-editor-grid">
            {(
              [
                ['platform', '링크 플랫폼*', 60],
                ['url', '링크 URL*', 2000],
                ['sourceUrl', '링크 근거 URL*', 2000],
              ] as const
            ).map(([key, label, max]) => (
              <TextField
                key={key}
                label={label}
                maxLength={max}
                value={x[key]}
                onChange={(e) =>
                  change(
                    'links',
                    d.links.map((link, j) =>
                      j === i ? { ...link, [key]: e.target.value } : link,
                    ),
                  )
                }
                error={errors[`links[${i}].${key}`] ?? ''}
                description={key === 'platform' ? '최대 60자' : 'HTTPS URL'}
              />
            ))}
          </div>
          <Hint>
            확인 시각:{' '}
            <AdminTime
              value={
                base?.links.find(
                  (link) =>
                    link.platform === x.platform &&
                    link.url === x.url &&
                    link.sourceUrl === x.sourceUrl,
                )?.checkedAt
              }
              empty="저장 후 서버에서 확인"
            />
          </Hint>
          <Button
            className="justify-self-start"
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
      <div className="song-editor-grid">
        {d.karaoke.map((k, i) => (
          <div className="grid min-w-0 gap-4" key={k.provider}>
            <div className="grid gap-2">
              <span className="text-sm font-medium">{k.provider} 상태</span>
              <Button
                className="min-h-14 justify-start border border-line font-normal"
                aria-label={`${k.provider} 상태: ${k.status === 'UNKNOWN' ? '미확인' : k.status === 'REGISTERED' ? '등록 확인' : '미등록 확인'}`}
                onClick={() => setStatus(k.provider)}
              >
                {k.status === 'UNKNOWN'
                  ? '미확인'
                  : k.status === 'REGISTERED'
                    ? '등록 확인'
                    : '미등록 확인'}
              </Button>
              <FieldError message={errors[`karaoke[${i}].status`]} />
            </div>
            <TextField
              label={`${k.provider} 번호${k.status === 'REGISTERED' ? '*' : ''}`}
              disabled={k.status !== 'REGISTERED'}
              value={k.number}
              maxLength={40}
              onChange={(e) =>
                change(
                  'karaoke',
                  d.karaoke.map((x, j) =>
                    i === j ? { ...x, number: e.target.value } : x,
                  ),
                )
              }
              error={errors[`karaoke[${i}].number`] ?? ''}
              description="등록 확인 시 필수"
            />
            <TextField
              label={`${k.provider} 근거 URL${k.status === 'UNKNOWN' ? ' · 선택' : '*'}`}
              value={k.sourceUrl}
              maxLength={2000}
              onChange={(e) =>
                change(
                  'karaoke',
                  d.karaoke.map((x, j) =>
                    i === j ? { ...x, sourceUrl: e.target.value } : x,
                  ),
                )
              }
              error={errors[`karaoke[${i}].sourceUrl`] ?? ''}
              description="미등록·등록 확인 시 필수 · HTTPS URL"
            />
            <Hint>
              확인 시각:{' '}
              <AdminTime
                value={
                  base?.karaoke.find((x) => x.provider === k.provider)
                    ?.checkedAt
                }
                empty="미확인"
              />{' '}
              · 조회 전용
            </Hint>
            <FieldError message={errors[`karaoke[${i}].provider`]} />
          </div>
        ))}
        <TextField
          label="변경 사유*"
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
          error={errors.reason ?? ''}
          description="모든 등록·수정에서 필수 · 최대 500자"
        />
      </div>
      <FieldError message={errors.karaoke} />
      {status && (
        <Dialog
          labelledBy="work-karaoke-title"
          onDismiss={() => setStatus(null)}
        >
          <h2 id="work-karaoke-title" className="mb-6 text-lg font-bold">
            {status} 확인 상태
          </h2>
          <div className="grid gap-3">
            {(
              [
                ['UNKNOWN', '미확인'],
                ['NOT_LISTED', '미등록 확인'],
                ['REGISTERED', '등록 확인'],
              ] as const
            ).map(([value, label]) => (
              <Button
                key={value}
                onClick={() => {
                  change(
                    'karaoke',
                    d.karaoke.map((x) =>
                      x.provider === status
                        ? {
                            ...x,
                            status: value,
                            number: value === 'REGISTERED' ? x.number : '',
                          }
                        : x,
                    ),
                  )
                  setStatus(null)
                }}
              >
                {label}
              </Button>
            ))}
            <Button onClick={() => setStatus(null)}>취소</Button>
          </div>
        </Dialog>
      )}
    </>
  )
}
