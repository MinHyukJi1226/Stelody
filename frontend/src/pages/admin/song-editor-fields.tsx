import { AuditButton } from './audit-button'
import { songLabel } from '@/features/admin/format'
import type { Draft } from '@/features/admin/song-draft'
import type { AdminSong } from '@/features/admin/songs'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { AdminTime } from './admin-shared'
import { Choices, FieldError, Hint, ReadValue, SongCard } from './song-ui'
import type { Selection } from './song-selector'
export type DraftChange = <K extends keyof Draft>(
  key: K,
  value: Draft[K],
) => void
export function SongEditorFields({
  draft: d,
  base,
  tab,
  errors,
  change,
  select,
  editWorkArtists,
  names,
  workArtists,
  labelState,
  alias,
  setAlias,
  addAlias,
  aliasError,
}: {
  draft: Draft
  base: AdminSong
  tab: string
  errors: Record<string, string>
  change: DraftChange
  select: (v: Selection) => void
  editWorkArtists: () => void
  names: Record<string, string>
  workArtists: string[]
  labelState: string
  alias: string
  setAlias: (v: string) => void
  addAlias: () => void
  aliasError: string
}) {
  const named = (ids: string[]) =>
    ids.length ? ids.map((id) => names[id] ?? labelState).join(' · ') : '없음'
  const video = base.videos.find((x) => x.id === d.representativeVideoId)
  const field = (
    key: 'title' | 'specialEventLabel' | 'recommendedSearchQuery',
    label: string,
    maxLength: number,
    description?: string,
  ) => (
    <TextField
      name={key}
      label={label}
      value={d[key]}
      maxLength={maxLength}
      onChange={(e) => change(key, e.target.value)}
      error={errors[key] ?? ''}
      description={description ?? ''}
    />
  )
  return (
    <div className="song-editor-grid">
      {tab === 'basic' && (
        <>
          <div className="grid content-start gap-6">
            <SongCard title="기본 정보">
              {field(
                'title',
                '곡명*',
                300,
                '최대 300자 · 표시 제목과 검색 별칭은 별개입니다.',
              )}
              <Choices
                label="곡 종류*"
                value={d.type}
                values={[
                  ['ORIGINAL', '오리지널'],
                  ['COVER', '커버'],
                ]}
                onChange={(v) => change('type', v)}
                error={errors.type ?? ''}
              />
              <div className="grid gap-3">
                <p className="text-sm font-medium">검색 별칭 · 선택</p>
                {d.aliases.map((value, i) => (
                  <div key={i} className="grid gap-2">
                    <div className="flex items-center justify-between gap-3">
                      <span className="min-w-0 break-words">{value}</span>
                      <Button
                        aria-label={`별칭 ${value} 삭제`}
                        onClick={() =>
                          change(
                            'aliases',
                            d.aliases.filter((_, j) => j !== i),
                          )
                        }
                      >
                        삭제
                      </Button>
                    </div>
                    <FieldError message={errors[`aliases[${i}]`]} />
                  </div>
                ))}
                <TextField
                  label="새 별칭"
                  placeholder="별칭을 입력하세요"
                  maxLength={300}
                  value={alias}
                  onChange={(e) => setAlias(e.target.value)}
                  error={aliasError || errors.aliases || ''}
                  description="최대 50개 · 각 300자 · 공백·대소문자가 같은 별칭은 중복할 수 없습니다."
                />
                <Button
                  disabled={!alias.trim() || d.aliases.length >= 50}
                  onClick={addAlias}
                >
                  별칭 추가
                </Button>
              </div>
            </SongCard>
            <SongCard title="참여자">
              <ReadValue label="참여 멤버">{named(d.memberIds)}</ReadValue>
              <Hint>저장 시 선택한 멤버는 확정 참여자로 반영됩니다.</Hint>
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => select('members')}>멤버 선택</Button>
                <Button
                  disabled={!d.memberIds.length}
                  onClick={() => change('memberIds', [])}
                >
                  선택 멤버 제거
                </Button>
              </div>
              <FieldError message={errors.memberIds} />
              <ReadValue label="외부 아티스트 · 선택">
                {named(d.externalArtistIds)}
              </ReadValue>
              <Button onClick={() => select('artists')}>
                외부 아티스트 선택
              </Button>
              <FieldError message={errors.externalArtistIds} />
              <Hint>
                참여 외부 아티스트는 이 곡에 참여한 사람을 뜻합니다. 원곡
                아티스트와 별개입니다.
              </Hint>
            </SongCard>
          </div>
          <div className="grid content-start gap-6">
            <SongCard title="원곡·대표 영상">
              <ReadValue label="연결할 원곡 · 선택">
                {d.workId
                  ? (names[d.workId] ?? labelState)
                  : '연결된 원곡 없음'}
              </ReadValue>
              <Button onClick={() => select('works')}>원곡 선택</Button>
              <FieldError message={errors.workId} />
              <ReadValue label="원곡 아티스트">
                {labelState === '확인됨' ? named(workArtists) : labelState}
              </ReadValue>
              <Button onClick={editWorkArtists}>원곡 아티스트 편집</Button>
              <Hint>
                여러 곡이 공유하는 원곡 정보입니다. 원곡 아티스트는 별도로
                저장하며, 입력 중인 곡 정보는 그대로 유지됩니다.
              </Hint>
              <ReadValue label="대표 영상 · 이 곡에 연결된 영상에서 선택">
                {video?.sourceTitle ??
                  video?.youtubeId ??
                  (d.representativeVideoId
                    ? '현재 연결된 영상에서 확인할 수 없음'
                    : '선택된 영상 없음')}
              </ReadValue>
              <Button onClick={() => select('video')}>대표 영상 선택</Button>
              {video && (
                <AuditButton
                  kind="videos"
                  id={video.id}
                  label="영상 변경 이력"
                />
              )}
              <FieldError message={errors.representativeVideoId} />
              <ReadValue label="영상 정보 · 조회 전용">
                {video ? (
                  <>
                    {songLabel(video.kind)} · {songLabel(video.availability)}
                    <br />
                    공개일{' '}
                    <AdminTime value={video.publishedAt} empty="기록 없음" />
                    <br />
                    썸네일{' '}
                    {video.thumbnailUrl
                      ? '등록된 이미지 사용'
                      : '등록된 이미지 없음'}
                  </>
                ) : (
                  '선택된 영상 없음'
                )}
              </ReadValue>
              <Hint>
                영상 공개일·영상 종류·썸네일은 별도 영상 수정 API 항목이며 이번
                저장에 포함하지 않습니다.
              </Hint>
            </SongCard>
          </div>
        </>
      )}
      {tab === 'publish' && (
        <>
          <SongCard title="공개 설정·특별 목적">
            <Choices
              label="공개 설정*"
              value={d.visibility}
              values={[
                ['DRAFT', '초안'],
                ['PUBLISHED', '공개 설정'],
                ['HIDDEN', '숨김'],
              ]}
              onChange={(v) => change('visibility', v)}
              error={errors.visibility ?? ''}
            />
            <Hint>
              공개 설정과 정보 완성도는 별개입니다. 실제 노출에는 영상·참여자
              조건이 별도로 적용됩니다.
            </Hint>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={d.isSpecialEvent}
                onChange={(e) => change('isSpecialEvent', e.target.checked)}
              />
              특별 목적 곡
            </label>
            <FieldError message={errors.isSpecialEvent} />
            {d.isSpecialEvent &&
              field(
                'specialEventLabel',
                '특별 목적 표시 문구*',
                60,
                '최대 60자 · 등록한 문구 그대로 표시됩니다.',
              )}
            <Hint>
              관리자가 확인 후 직접 설정합니다. 자동 공개 정책을 활성화하지
              않습니다.
            </Hint>
          </SongCard>
          <SongCard title="검색 확인">
            <Choices
              label="검색 확인*"
              value={d.searchVisibility}
              values={[
                ['UNCHECKED', '미확인'],
                ['NORMAL', '정상 검색'],
                ['DIFFICULT', '검색 어려움'],
              ]}
              onChange={(v) => change('searchVisibility', v)}
              error={errors.searchVisibility ?? ''}
            />
            {field(
              'recommendedSearchQuery',
              d.searchVisibility === 'DIFFICULT'
                ? '권장 검색어*'
                : '권장 검색어 · 선택',
              300,
              '검색 어려움은 필수 · 최대 300자',
            )}
            <ReadValue label="검색 확인일 · 조회 전용">
              <AdminTime value={base.searchCheckedAt} empty="확인 기록 없음" />
            </ReadValue>
            <Hint>
              확인일은 서버에서 관리합니다. 미확인으로 저장하면 확인일과 권장
              검색어가 초기화됩니다.
            </Hint>
          </SongCard>
        </>
      )}
      {tab === 'links' && (
        <>
          <SongCard title="곡 링크">
            {d.links.map((link, i) => (
              <div className="grid gap-4" key={i}>
                <h3 className="font-bold">링크 {i + 1}</h3>
                {(
                  [
                    ['platform', '플랫폼*', 60],
                    ['url', '링크 URL*', 2000],
                    ['sourceUrl', '출처 URL*', 2000],
                  ] as const
                ).map(([key, label, maxLength]) => (
                  <TextField
                    key={key}
                    name={`links[${i}].${key}`}
                    label={label}
                    value={link[key]}
                    maxLength={maxLength}
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
                <Button
                  onClick={() =>
                    change(
                      'links',
                      d.links.filter((_, j) => i !== j),
                    )
                  }
                >
                  링크 삭제
                </Button>
              </div>
            ))}
            <FieldError message={errors.links} />
            <Button
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
            <Hint>
              최대 20개 · URL은 HTTPS를 사용합니다. 곡 링크만 편집하며 원곡
              링크는 저장하지 않습니다.
            </Hint>
          </SongCard>
          <SongCard title="곡 노래방 정보">
            {d.karaoke.map((k, i) => (
              <div key={k.provider} className="grid gap-4">
                <Choices
                  label={`${k.provider} 확인 상태`}
                  value={k.status}
                  values={[
                    ['UNKNOWN', '미확인'],
                    ['NOT_LISTED', '미등록 확인'],
                    ['REGISTERED', '등록 확인'],
                  ]}
                  onChange={(v) =>
                    change(
                      'karaoke',
                      d.karaoke.map((x, j) =>
                        j === i ? { ...x, status: v } : x,
                      ),
                    )
                  }
                  error={errors[`karaoke[${i}].status`]}
                />
                {k.status === 'REGISTERED' && (
                  <TextField
                    label={`${k.provider} 번호*`}
                    name={`karaoke[${i}].number`}
                    maxLength={40}
                    value={k.number}
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
                )}
                <TextField
                  label={`${k.provider} 확인 출처 URL${k.status === 'UNKNOWN' ? ' · 선택' : '*'}`}
                  name={`karaoke[${i}].sourceUrl`}
                  maxLength={2000}
                  value={k.sourceUrl}
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
              </div>
            ))}
            <FieldError message={errors.karaoke} />
            <Hint>
              미등록 확인은 근거가 필요하고 번호는 저장하지 않습니다. 미확인은
              별도 상태입니다. 원곡의 노래방 정보는 편집하지 않습니다.
            </Hint>
          </SongCard>
        </>
      )}
    </div>
  )
}
