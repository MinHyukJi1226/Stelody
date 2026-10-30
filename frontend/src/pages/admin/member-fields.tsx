import { useState } from 'react'
import { type MemberDraft, validMemberUrl } from '@/features/admin/members'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { Artwork } from '@/shared/ui/artwork'
import { TextField } from '@/shared/ui/input'
import { FieldError } from './song-ui'

export function MemberFields({
  draft: d,
  change,
  errors,
  alias,
  setAlias,
  addAlias,
  reason,
  setReason,
}: {
  draft: MemberDraft
  change: <K extends keyof MemberDraft>(key: K, value: MemberDraft[K]) => void
  errors: Record<string, string>
  alias: string
  setAlias: (v: string) => void
  addAlias: () => void
  reason: string
  setReason: (v: string) => void
}) {
  const [activityOpen, setActivityOpen] = useState(false)
  return (
    <>
      <div role="img" aria-label="프로필 미리보기">
        <Artwork
          src={
            validMemberUrl(d.profileImageUrl.trim())
              ? d.profileImageUrl.trim()
              : null
          }
          className="size-20 rounded-2xl bg-tint"
          fallbackText="사진"
        />
      </div>
      <div className="song-editor-grid">
        <TextField
          label="멤버 이름*"
          value={d.name}
          maxLength={100}
          onChange={(e) => change('name', e.target.value)}
          error={errors.name ?? ''}
          description="최대 100자"
        />
        <TextField
          label="유닛명"
          value={d.unitName}
          maxLength={100}
          onChange={(e) => change('unitName', e.target.value)}
          error={errors.unitName ?? ''}
          description="기수와 별도 관리 · 최대 100자 · 선택"
        />
        <TextField
          label="기수"
          value={d.generation}
          inputMode="numeric"
          onChange={(e) => change('generation', e.target.value)}
          error={errors.generation ?? ''}
          description="1–32767 또는 미등록"
        />
        <div className="grid gap-2">
          <span className="text-sm font-medium">활동 상태*</span>
          <Button
            className="min-h-14 justify-start border border-line font-normal"
            aria-label={`활동 상태: ${d.activityStatus === 'ACTIVE' ? '활동 중' : '졸업'}`}
            onClick={() => setActivityOpen(true)}
          >
            {d.activityStatus === 'ACTIVE' ? '활동 중' : '졸업'}
          </Button>
          <FieldError message={errors.activityStatus} />
        </div>
        <TextField
          label="프로필 이미지 URL"
          value={d.profileImageUrl}
          maxLength={2000}
          onChange={(e) => change('profileImageUrl', e.target.value)}
          error={errors.profileImageUrl ?? ''}
          description="HTTPS 이미지 URL · 선택"
        />
        <TextField
          label="치지직 공식 링크"
          value={d.chzzkUrl}
          maxLength={2000}
          onChange={(e) => change('chzzkUrl', e.target.value)}
          error={errors.chzzkUrl ?? ''}
          description="HTTPS URL · 선택"
        />
        <TextField
          label="X 공식 링크"
          value={d.xUrl}
          maxLength={2000}
          onChange={(e) => change('xUrl', e.target.value)}
          error={errors.xUrl ?? ''}
          description="HTTPS URL · 선택"
        />
        <TextField
          label="생일 · 월 / 일"
          value={d.birthday}
          placeholder="월 / 일"
          onChange={(e) => change('birthday', e.target.value)}
          error={
            errors.birthday ?? errors.birthdayMonth ?? errors.birthdayDay ?? ''
          }
          description="둘 다 입력하거나 모두 비움 · 미등록이면 비워주세요."
        />
        <TextField
          label="데뷔일"
          type="date"
          value={d.debutDate}
          onChange={(e) => change('debutDate', e.target.value)}
          error={errors.debutDate ?? ''}
          description="미등록이면 비워주세요."
        />
        <div className="grid gap-3">
          <TextField
            label="검색 별칭 · 선택"
            placeholder="새 별칭 입력"
            value={alias}
            maxLength={200}
            onChange={(e) => setAlias(e.target.value)}
            error={errors.aliases ?? ''}
            description="최대 50개 · 각 200자"
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
                      d.aliases.filter((_, j) => i !== j),
                    )
                  }
                >
                  {value} ×
                </Button>
                <FieldError message={errors[`aliases[${i}]`]} />
              </div>
            ))}
          </div>
          <Button
            variant="primary"
            className="justify-self-start"
            disabled={d.aliases.length >= 50}
            onClick={addAlias}
          >
            별칭 추가
          </Button>
        </div>
        <TextField
          label="변경 사유*"
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
          error={errors.reason ?? ''}
          description="모든 등록·수정에서 필수 · 최대 500자"
        />
      </div>
      <Button variant="primary" className="justify-self-start" disabled>
        공식 YouTube 채널 관리 · 준비 중
      </Button>
      {activityOpen && (
        <Dialog
          labelledBy="member-activity-title"
          onDismiss={() => setActivityOpen(false)}
        >
          <h2 id="member-activity-title" className="mb-6 text-lg font-bold">
            활동 상태
          </h2>
          <div className="grid gap-3">
            {(
              [
                ['ACTIVE', '활동 중'],
                ['GRADUATED', '졸업'],
              ] as const
            ).map(([value, label]) => (
              <Button
                key={value}
                aria-pressed={d.activityStatus === value}
                onClick={() => {
                  change('activityStatus', value)
                  setActivityOpen(false)
                }}
              >
                {label}
              </Button>
            ))}
            <Button onClick={() => setActivityOpen(false)}>취소</Button>
          </div>
        </Dialog>
      )}
    </>
  )
}
