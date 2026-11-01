import { AuditButton, AuditScope } from './audit-button'
import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAdminUser } from '@/features/admin/admin-context'
import { isActiveAdmin } from '@/features/admin/api'
import { isAdminAccessError } from '@/features/admin/format'
import { sessionOptions } from '@/features/auth/session'
import { equal } from '@/features/admin/song-draft'
import {
  rulesOptions,
  getRules,
  writeRules,
  previewRules,
  validateRules,
  validateSamples,
  ruleGroups,
  availability,
  type RuleSnapshot,
  type Configuration,
  type Sample,
  type PreviewResult,
} from '@/features/admin/collection-rules'
import { ApiError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { TextField } from '@/shared/ui/input'
import { Dialog } from '@/shared/ui/dialog'
import { useToast } from '@/shared/ui/toast-context'
import { AdminApiAccess, AdminAccess, AdminTable } from './admin-shared'
import { SongNav, FieldError } from './song-ui'
import { SongLoading } from './admin-songs'
import { useCatalogWrite } from './use-catalog-write'
import { useWorkNavigation } from './use-work-navigation'

export function AdminCollectionRulesPage() {
  const user = useAdminUser(),
    q = useQuery(rulesOptions(user))
  if (isAdminAccessError(q.error))
    return <AdminApiAccess error={q.error} user={user} />
  if (q.data) return <RulesEditor initial={q.data} />
  return (
    <>
      <SongNav />
      <h1 className="admin-heading">수집 분류 규칙</h1>
      {q.isPending ? (
        <SongLoading />
      ) : (
        <section className="admin-panel" role="alert">
          <h2 className="text-lg font-bold">분류 규칙을 조회하지 못했습니다</h2>
          <Button onClick={() => void q.refetch()}>다시 조회</Button>
        </section>
      )}
    </>
  )
}
function RulesEditor({ initial }: { initial: RuleSnapshot }) {
  const user = useAdminUser(),
    session = useQuery({
      ...sessionOptions,
      refetchOnMount: false,
      retryOnMount: false,
      refetchOnWindowFocus: false,
    }),
    notify = useToast()
  const [accessError, setAccessError] = useState<unknown>(null),
    edit = useCatalogWrite(initial, { user, onAccessError: setAccessError })
  const [draft, setDraft] = useState(() =>
      structuredClone(initial.configuration),
    ),
    [reason, setReason] = useState(''),
    [view, setView] = useState<'edit' | 'preview'>('edit'),
    [samples, setSamples] = useState<Sample[]>([
      { title: '', availability: 'PUBLIC' },
    ])
  const [pending, setPending] = useState(false),
    [previewError, setPreviewError] = useState(''),
    [sampleErrors, setSampleErrors] = useState<Record<string, string>>({}),
    [result, setResult] = useState<{
      key: string
      value: PreviewResult
    } | null>(null)
  const request = useRef<AbortController | null>(null),
    mounted = useRef(false)
  const validSession =
    session.isSuccess &&
    isActiveAdmin(session.data) &&
    session.data?.id === user
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      request.current?.abort()
    }
  }, [])
  useEffect(() => {
    if (!validSession) request.current?.abort()
  }, [validSession])
  const busy = edit.busy || pending,
    locked = busy || !validSession || edit.mode !== 'edit'
  const key = JSON.stringify([edit.base.version, draft, samples])
  const dirty =
    !equal(draft, edit.base.configuration) ||
    !!reason ||
    edit.uncertain ||
    samples.some((s) => !!s.title) ||
    samples.length !== 1
  const nav = useWorkNavigation(
    dirty,
    busy,
    edit.uncertain,
    '/admin',
    '저장하지 않은 규칙·변경 사유·샘플 입력은 버려집니다.',
  )
  function change(
    group: keyof Configuration,
    index: number,
    patch: Partial<Configuration['cover'][number]>,
  ) {
    setDraft((old) => ({
      ...old,
      [group]: old[group].map((m, i) => (i === index ? { ...m, ...patch } : m)),
    }))
    edit.setErrors({})
  }
  function confirm() {
    const errors = validateRules(draft, reason)
    edit.setErrors(errors)
    if (Object.keys(errors).length) {
      setView('edit')
      return
    }
    if (!locked) edit.setMode('confirm')
  }
  async function preview() {
    if (request.current || locked || !edit.verified()) return
    const errors = { ...validateRules(draft), ...validateSamples(samples) }
    setSampleErrors(errors)
    if (Object.keys(errors).length) return
    const abort = new AbortController()
    request.current = abort
    setPending(true)
    setPreviewError('')
    setResult(null)
    try {
      const value = await previewRules(
        { version: edit.base.version, configuration: draft, samples },
        abort.signal,
      )
      if (!abort.signal.aborted && mounted.current && edit.verified())
        setResult({ key, value })
    } catch (e) {
      if (!mounted.current || abort.signal.aborted || !edit.verified()) return
      if (isAdminAccessError(e)) setAccessError(e)
      else if (
        e instanceof ApiError &&
        e.status === 409 &&
        e.code === 'CATALOG_VERSION_CONFLICT'
      ) {
        edit.setMode('conflict')
        setView('edit')
        edit.setNotice(
          '샘플 판정' +
            ' 기준 규칙이 변경되었습니다. 최신 버전을 비교한 뒤 다시 판정해주세요.',
        )
      } else {
        setPreviewError(
          '샘플 판정에 실패했습니다. 입력 규칙과 샘플을 유지했습니다.',
        )
        if (e instanceof ApiError)
          setSampleErrors(
            Object.fromEntries(e.fieldErrors.map((x) => [x.field, x.message])),
          )
      }
    } finally {
      request.current = null
      if (mounted.current) setPending(false)
    }
  }
  if (isAdminAccessError(accessError))
    return (
      <>
        <AdminApiAccess error={accessError} user={user} />
        {nav.dialog}
      </>
    )
  if (session.isError)
    return (
      <>
        <AdminAccess state="error" onRetry={() => void session.refetch()} />
        {nav.dialog}
      </>
    )
  const comparing = ['conflict', 'unknown', 'checked'].includes(edit.mode)
  return (
    <AuditScope dirty={dirty} busy={busy} uncertain={edit.uncertain}>
      <SongNav />
      <h1 className="admin-heading">
        {view === 'preview' ? '변경할 규칙으로 샘플 판정' : '수집 분류 규칙'}
      </h1>
      <AuditButton kind="collection-rules" label="규칙 변경 이력" />
      {edit.notice && (
        <p role="alert" className="admin-notice text-error">
          {edit.notice}
        </p>
      )}
      {!!Object.keys(edit.errors).length && (
        <section role="alert" className="admin-notice">
          <h2 className="font-bold">입력 내용을 확인해 주세요</h2>
          {Object.entries(edit.errors).map(([k, v]) => (
            <p key={k} className="text-error">
              {v}
            </p>
          ))}
        </section>
      )}
      {comparing && (
        <section className="admin-panel" role="status">
          <h2 className="text-lg font-bold">
            {edit.mode === 'conflict'
              ? '다른 관리자가 먼저 변경했습니다'
              : edit.latest
                ? '최신 내용을 확인했습니다'
                : '저장 결과를 확인할 수 없습니다'}
          </h2>
          <p className="text-sm text-muted">
            최초 값·내 입력·최신 값을 비교하고 최신 버전으로 재확인해주세요.
            현재 값이 같아도 이 요청의 성공을 증명하지 않습니다. 자동 재전송하지
            않습니다.
          </p>
          {edit.latest && (
            <AdminTable
              headers={['항목', '최초 조회값', '내 입력', '최신 저장값']}
              widths={[16, 28, 28, 28]}
              empty=""
              rows={ruleGroups.map(([g, label]) => ({
                key: g,
                cells: [
                  label,
                  <Markers values={edit.base.configuration[g]} />,
                  <Markers values={draft[g]} />,
                  <Markers values={edit.latest!.configuration[g]} />,
                ],
              }))}
            />
          )}
          <div className="flex flex-wrap gap-2">
            {edit.latest ? (
              <Button
                variant="primary"
                disabled={busy}
                onClick={() => {
                  edit.review()
                  setResult(null)
                }}
              >
                입력 재확인
              </Button>
            ) : (
              <Button
                variant="primary"
                disabled={busy}
                onClick={() => void edit.check(getRules)}
              >
                최신 저장값 조회
              </Button>
            )}
            <Button disabled={busy} onClick={nav.cancel}>
              작업함으로
            </Button>
          </div>
        </section>
      )}
      {view === 'edit' ? (
        <>
          <Notice>
            단어 일치는 앞뒤가 글자·숫자가 아닌 경우에만 적용합니다. 문구 일치는
            포함 여부로 판정합니다. 대소문자·공백은 정규화하며 정규식은 지원하지
            않습니다.
          </Notice>
          <Notice>
            규칙 저장은 자동 공개를 활성화하지 않습니다. 허용 채널은 공식 채널
            관리에서 변경하세요.
          </Notice>
          <fieldset
            disabled={locked}
            className="grid min-w-0 gap-6"
            aria-busy={busy}
          >
            <div className="song-editor-grid">
              {ruleGroups.map(([g, label]) => (
                <section
                  className="grid min-w-0 content-start gap-4"
                  key={g}
                  aria-label={label}
                >
                  <h2 className="text-lg font-bold">{label}</h2>
                  <FieldError message={edit.errors[`configuration.${g}`]} />
                  {draft[g].length === 0 && (
                    <p className="text-sm text-muted">
                      등록된 규칙이 없습니다.
                    </p>
                  )}
                  {draft[g].map((m, i) => (
                    <div
                      key={i}
                      className="grid gap-2 rounded-xl bg-surface p-4"
                    >
                      <TextField
                        label={`${label} ${i + 1} 단어·문구*`}
                        maxLength={60}
                        value={m.text}
                        onChange={(e) => change(g, i, { text: e.target.value })}
                        error={
                          edit.errors[`configuration.${g}[${i}].text`] ?? ''
                        }
                      />
                      <div className="flex flex-wrap gap-2">
                        <Choice
                          label={`${label} ${i + 1} 일치 방식`}
                          value={m.match}
                          options={{ WORD: '단어', PHRASE: '문구' }}
                          onChange={(match) =>
                            change(g, i, { match: match as 'WORD' | 'PHRASE' })
                          }
                        />
                        <Button
                          onClick={() => {
                            setDraft((old) => ({
                              ...old,
                              [g]: old[g].filter((_, j) => i !== j),
                            }))
                            edit.setErrors({})
                          }}
                        >
                          규칙 삭제
                        </Button>
                      </div>
                      <FieldError
                        message={edit.errors[`configuration.${g}[${i}].match`]}
                      />
                    </div>
                  ))}
                  <Button
                    variant="primary"
                    className="justify-self-start"
                    disabled={draft[g].length >= 30}
                    onClick={() =>
                      setDraft((old) => ({
                        ...old,
                        [g]: [...old[g], { text: '', match: 'WORD' }],
                      }))
                    }
                  >
                    규칙 추가
                  </Button>
                </section>
              ))}
              <TextField
                label="변경 사유*"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
                error={edit.errors.reason ?? ''}
                description="최대 500자 · 모든 수정에서 필수"
              />
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                onClick={() => {
                  const errors = validateRules(draft)
                  edit.setErrors(errors)
                  if (!Object.keys(errors).length) setView('preview')
                }}
              >
                샘플 판정
              </Button>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="primary" onClick={confirm}>
                변경 저장
              </Button>
              <Button onClick={nav.cancel}>취소</Button>
            </div>
          </fieldset>
        </>
      ) : (
        <>
          <p className="text-sm text-muted">
            아직 저장되지 않은 입력 규칙으로 판정합니다. 제목·공개 상태를 최대
            20개 입력하세요.
          </p>
          {previewError && (
            <p className="admin-notice text-error" role="alert">
              {previewError}
            </p>
          )}
          {!!Object.keys(sampleErrors).length && (
            <section className="admin-notice" role="alert">
              <h2 className="font-bold">판정 입력을 확인해 주세요</h2>
              {Object.entries(sampleErrors).map(([field, message]) => (
                <p key={field} className="text-sm text-error">
                  {message}
                </p>
              ))}
            </section>
          )}
          <fieldset
            disabled={locked}
            className="grid min-w-0 gap-6"
            aria-busy={pending}
          >
            {samples.map((s, i) => (
              <section
                key={i}
                className="grid gap-4"
                aria-label={`샘플 ${i + 1}`}
              >
                <TextField
                  label={`샘플 ${i + 1} 제목*`}
                  value={s.title}
                  maxLength={500}
                  onChange={(e) =>
                    setSamples((old) =>
                      old.map((v, j) =>
                        j === i ? { ...v, title: e.target.value } : v,
                      ),
                    )
                  }
                  error={sampleErrors[`samples[${i}].title`] ?? ''}
                />
                <Choice
                  field
                  label={`샘플 ${i + 1} 영상 공개 상태`}
                  value={s.availability}
                  options={Object.fromEntries(availability.map((x) => [x, x]))}
                  onChange={(v) =>
                    setSamples((old) =>
                      old.map((x, j) =>
                        j === i
                          ? { ...x, availability: v as Sample['availability'] }
                          : x,
                      ),
                    )
                  }
                />
                <FieldError
                  message={sampleErrors[`samples[${i}].availability`]}
                />
                <Button
                  className="justify-self-start"
                  onClick={() =>
                    setSamples((old) => old.filter((_, j) => i !== j))
                  }
                >
                  샘플 삭제
                </Button>
              </section>
            ))}
            <FieldError message={sampleErrors.samples} />
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={samples.length >= 20}
                onClick={() =>
                  setSamples((old) => [
                    ...old,
                    { title: '', availability: 'PUBLIC' },
                  ])
                }
              >
                샘플 추가
              </Button>
              <Button variant="primary" onClick={() => void preview()}>
                샘플 판정하기
              </Button>
              <Button onClick={() => setView('edit')}>
                규칙 입력으로 돌아가기
              </Button>
            </div>
          </fieldset>
          {pending && <p role="status">샘플을 판정하고 있습니다…</p>}
          {result && result.key !== key && (
            <p role="status" className="admin-notice">
              입력이나 기준 버전이 변경되었습니다. 샘플을 다시 판정해주세요.
            </p>
          )}
          {result?.key === key && (
            <section className="admin-panel" role="status">
              <h2 className="text-lg font-bold">샘플 판정 결과</h2>
              <p className="text-sm text-muted">
                기준 버전 {result.value.baseVersion} · 분류 결과이며 검토 상태
                변경이나 자동 공개가 아닙니다.
              </p>
              {result.value.decisions.map((d, i) => (
                <div key={i} className="admin-notice">
                  <h3 className="font-bold">{samples[i]!.title}</h3>
                  <p>
                    {samples[i]!.availability} · {d.disposition} /{' '}
                    {d.suggestedType}
                  </p>
                  <p className="text-sm text-muted">
                    {d.reason} · {d.version}
                  </p>
                </div>
              ))}
              <Button variant="primary" disabled={locked} onClick={confirm}>
                이 규칙 저장 확인
              </Button>
            </section>
          )}
        </>
      )}
      {edit.mode === 'confirm' && (
        <Dialog
          labelledBy="rule-confirm"
          busy={edit.busy}
          onDismiss={() => {
            if (!edit.busy) edit.setMode('edit')
          }}
        >
          <h2 id="rule-confirm" className="text-lg font-bold">
            {edit.busy ? '저장 중입니다' : '분류 규칙을 저장할까요?'}
          </h2>
          <p className="my-6 text-sm text-muted">
            분류 규칙과 변경 사유를 확인해주세요. 자동 공개를 활성화하지
            않습니다.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="primary"
              disabled={edit.busy}
              onClick={() =>
                void edit.save(
                  (signal) =>
                    writeRules(
                      {
                        version: edit.base.version,
                        configuration: draft,
                        reason: reason.trim(),
                      },
                      signal,
                    ),
                  () => {
                    notify('변경사항을 저장했습니다.')
                    nav.go('/admin')
                  },
                )
              }
            >
              확인 후 저장
            </Button>
            <Button disabled={edit.busy} onClick={() => edit.setMode('edit')}>
              취소
            </Button>
          </div>
        </Dialog>
      )}
      {nav.dialog}
    </AuditScope>
  )
}
function Markers({ values }: { values: Configuration['cover'] }) {
  return (
    <div className="grid gap-2">
      {values.length
        ? values.map((v, i) => (
            <p key={i} className="whitespace-pre-wrap break-words">
              {v.text} · {v.match === 'WORD' ? '단어' : '문구'}
            </p>
          ))
        : '없음'}
    </div>
  )
}
function Notice({ children }: { children: React.ReactNode }) {
  return (
    <section className="admin-panel gap-2">
      <h2 className="font-bold">확인해 주세요</h2>
      <p className="text-sm text-muted">{children}</p>
    </section>
  )
}
function Choice({
  field = false,
  label,
  value,
  options,
  onChange,
}: {
  field?: boolean
  label: string
  value: string
  options: Record<string, string>
  onChange: (v: string) => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <div className={field ? 'grid gap-2' : undefined}>
      {field && <span className="text-sm font-medium">영상 공개 상태*</span>}
      <Button
        variant={field ? 'secondary' : 'primary'}
        className={
          field
            ? 'min-h-14 justify-start border border-line font-normal'
            : undefined
        }
        aria-label={label}
        onClick={() => setOpen(true)}
      >
        {options[value]} ▾
      </Button>
      {open && (
        <Dialog labelledBy="rule-choice" onDismiss={() => setOpen(false)}>
          <h2 id="rule-choice" className="mb-6 text-lg font-bold">
            {label}
          </h2>
          <div className="grid gap-3">
            {Object.entries(options).map(([v, l]) => (
              <Button
                key={v}
                aria-pressed={value === v}
                onClick={() => {
                  onChange(v)
                  setOpen(false)
                }}
              >
                {l}
              </Button>
            ))}
            <Button onClick={() => setOpen(false)}>취소</Button>
          </div>
        </Dialog>
      )}
    </div>
  )
}
