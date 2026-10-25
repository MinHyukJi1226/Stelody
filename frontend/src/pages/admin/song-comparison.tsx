import { songLabel } from '@/features/admin/format'
import type { AdminSong } from '@/features/admin/songs'
import {
  chosenDraft,
  equal,
  normalizedDraft,
  fields,
  toDraft,
  type Draft,
  type Field,
} from '@/features/admin/song-draft'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { AdminTable } from './admin-shared'
import { Hint, SongCard } from './song-ui'
export type Comparison = {
  latest: AdminSong
  choices: Partial<Record<Field, 'draft' | 'latest'>>
  stage: 'compare' | 'review'
}
export function SongComparison({
  base,
  draft,
  value,
  onChange,
  names,
  uncertain,
  ready,
  busy,
  reason,
  setReason,
  confirmed,
  setConfirmed,
  save,
}: {
  base: AdminSong
  draft: Draft
  value: Comparison
  onChange: (v: Comparison) => void
  names: Record<string, string>
  ready: boolean
  uncertain: boolean
  busy: boolean
  reason: string
  setReason: (v: string) => void
  confirmed: boolean
  setConfirmed: (v: boolean) => void
  save: (d: Draft, v: number) => void
}) {
  const latest = toDraft(value.latest)
  const final = normalizedDraft(chosenDraft(draft, latest, value.choices))
  const format = (key: Field, v: Draft[Field]): string => {
    if (key === 'memberIds' || key === 'externalArtistIds')
      return (
        (v as string[]).map((id) => names[id] ?? '이름 확인 중…').join(' · ') ||
        '없음'
      )
    if (key === 'workId')
      return v ? (names[String(v)] ?? '원곡 이름 확인 중…') : '없음'
    if (key === 'representativeVideoId')
      return v
        ? ([...value.latest.videos, ...base.videos].find((x) => x.id === v)
            ?.sourceTitle ?? '영상 제목 없음')
        : '없음'
    if (typeof v === 'boolean') return v ? '특별 목적' : '해당 없음'
    if (key === 'links')
      return (
        (v as Draft['links'])
          .map((x) => `${x.platform} · ${x.url} · 출처 ${x.sourceUrl}`)
          .join('\n') || '없음'
      )
    if (key === 'karaoke')
      return (v as Draft['karaoke'])
        .map(
          (x) =>
            `${x.provider} ${songLabel(x.status)} ${x.number} ${x.sourceUrl}`,
        )
        .join('\n')
    if (Array.isArray(v)) return v.join(' · ') || '없음'
    return v ? songLabel(String(v)) : '없음'
  }
  const choose = (key: Field, side: 'draft' | 'latest') =>
    onChange({ ...value, choices: { ...value.choices, [key]: side } })
  return (
    <SongCard
      title={
        value.stage === 'compare'
          ? '보존한 입력과 최신 값 비교'
          : '최종 저장값 재확인'
      }
    >
      <Hint>
        처음 읽은 버전 B: {base.version} · 최신 버전 S: {value.latest.version}
      </Hint>
      <p className="text-sm">
        {uncertain
          ? '현재 값이 내 입력과 같아도 앞선 요청의 성공을 증명하지 않습니다. '
          : ''}
        {value.stage === 'compare'
          ? '최신 값과 내 입력을 비교해주세요. 양쪽에서 다르게 변경한 항목은 직접 선택해야 합니다.'
          : '선택한 최종 값을 모두 확인해주세요. 변경 사유와 최신 버전으로 직접 저장해야 반영됩니다.'}
      </p>
      <fieldset disabled={busy} className="min-w-0">
        <AdminTable
          headers={['항목', '내 입력', '최신 저장값', '최종 저장값']}
          widths={[16, 27, 27, 30]}
          empty=""
          rows={fields.map(([key, label]) => ({
            key,
            cells: [
              <>
                <strong>{label}</strong>
                <details className="text-xs text-muted">
                  <summary>최초 값 (B)</summary>
                  {format(key, toDraft(base)[key])}
                </details>
              </>,
              <span className="whitespace-pre-wrap">
                {format(key, draft[key])}
              </span>,
              <span className="whitespace-pre-wrap">
                {format(key, latest[key])}
              </span>,
              <>
                {value.choices[key] ? (
                  <span className="whitespace-pre-wrap">
                    {format(key, final[key])}
                  </span>
                ) : (
                  <p className="text-warning">선택 필요</p>
                )}
                {value.stage === 'compare' &&
                  !equal(draft[key], latest[key]) && (
                    <div className="flex flex-wrap gap-2">
                      <Button
                        aria-pressed={value.choices[key] === 'draft'}
                        onClick={() => choose(key, 'draft')}
                      >
                        내 입력 사용
                      </Button>
                      <Button
                        aria-pressed={value.choices[key] === 'latest'}
                        onClick={() => choose(key, 'latest')}
                      >
                        최신값 사용
                      </Button>
                    </div>
                  )}
              </>,
            ],
          }))}
        />
      </fieldset>
      {value.stage === 'compare' ? (
        <Button
          variant="primary"
          disabled={
            !fields.every(([key]) => value.choices[key]) || busy || !ready
          }
          onClick={() => {
            onChange({ ...value, stage: 'review' })
            setConfirmed(false)
          }}
        >
          최종 값 검토
        </Button>
      ) : (
        <fieldset disabled={busy} className="grid gap-6">
          <TextField
            label="변경 사유*"
            name="reason"
            value={reason}
            maxLength={500}
            description="모든 수정에서 필수 · 최대 500자"
            onChange={(e) => setReason(e.target.value)}
          />
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            최종 값을 모두 확인했습니다
          </label>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="primary"
              disabled={!confirmed || !reason.trim() || !ready}
              onClick={() => save(final, value.latest.version)}
            >
              {busy ? '저장 중…' : '재확인 후 저장'}
            </Button>
            <Button onClick={() => onChange({ ...value, stage: 'compare' })}>
              비교 화면으로
            </Button>
          </div>
        </fieldset>
      )}
    </SongCard>
  )
}
