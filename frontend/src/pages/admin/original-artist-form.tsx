import { useEffect, useState } from 'react'
import {
  getArtist,
  saveArtist,
  validateArtist,
  type Artist,
} from '@/features/admin/original-artists'
import { Button } from '@/shared/ui/button'
import { Dialog } from '@/shared/ui/dialog'
import { CatalogWriteStatus } from './catalog-write-status'
import type { WorkArtistProps } from './work-artist-editor'
import { CatalogExit } from './catalog-exit'
import { useCatalogWrite } from './use-catalog-write'
import { Hint } from './song-ui'
import { ArtistFields } from './artist-fields'
import { ArtistRegistration } from './artist-registration'

export type ArtistEditorProps = WorkArtistProps & {
  initial: Artist | null
  onArtistSaved: (item: Artist, created: boolean) => void
}
export function ArtistEditor(props: ArtistEditorProps) {
  return props.initial ? (
    <ExistingArtistEditor {...props} initial={props.initial} />
  ) : (
    <ArtistRegistration {...props} />
  )
}
function ExistingArtistEditor({
  initial,
  onArtistSaved,
  ...props
}: Omit<ArtistEditorProps, 'initial'> & { initial: Artist }) {
  const edit = useCatalogWrite(initial, props)
  const [name, setName] = useState(initial.name)
  const [aliases, setAliases] = useState(initial.aliases)
  const [alias, setAlias] = useState('')
  const [reason, setReason] = useState(''),
    [exit, setExit] = useState(false)
  const dirty =
    name !== edit.base.name ||
    JSON.stringify(aliases) !== JSON.stringify(edit.base.aliases) ||
    !!alias ||
    !!reason ||
    edit.uncertain
  const { onState } = props
  useEffect(() => {
    onState({ dirty, busy: edit.busy, uncertain: edit.uncertain })
  }, [dirty, edit.busy, edit.uncertain, onState])
  function cancel() {
    if (edit.busy) return
    if (dirty) setExit(true)
    else props.onDismiss()
  }
  function confirm() {
    const errors = validateArtist(name, aliases, reason)
    if (alias.trim())
      errors.aliases = '입력 중인 별칭을 추가하거나 비운 뒤 저장해주세요.'
    edit.setErrors(errors)
    if (Object.keys(errors).length) return
    edit.setMode('confirm')
  }
  async function save() {
    await edit.save(
      (signal) =>
        saveArtist(
          initial.id,
          {
            version: edit.base.version,
            name: name.trim(),
            aliases,
            reason: reason.trim(),
          },
          signal,
        ),
      (item) => {
        onArtistSaved(item, false)
      },
    )
  }
  const describe = (item: Pick<Artist, 'name' | 'aliases'> | null) =>
    item ? (
      <>
        <strong>{item.name}</strong>
        <p className="text-xs text-muted">
          별칭 · {item.aliases.join(' · ') || '없음'}
        </p>
      </>
    ) : (
      '없음'
    )
  if (!props.enabled) return null
  return (
    <Dialog
      labelledBy="original-artist-title"
      onDismiss={cancel}
      busy={edit.busy}
      className="song-selector original-artist-dialog"
    >
      {exit ? (
        <CatalogExit
          onContinue={() => setExit(false)}
          onLeave={props.onDismiss}
          uncertain={edit.uncertain}
        />
      ) : edit.mode === 'confirm' ? (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            아티스트 정보를 저장할까요?
          </h2>
          <p className="text-sm text-muted">
            이 아티스트를 연결한 원곡과 외부 참여자 표시에도 변경이 반영될 수
            있습니다.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button disabled={edit.busy} onClick={() => edit.setMode('edit')}>
              취소
            </Button>
            <Button
              variant="primary"
              disabled={edit.busy}
              onClick={() => void save()}
            >
              {edit.busy ? '저장 중…' : '아티스트 정보 저장'}
            </Button>
          </div>
        </>
      ) : edit.mode !== 'edit' ? (
        <CatalogWriteStatus
          mode={edit.mode}
          busy={edit.busy}
          notice={edit.notice}
          base={describe(edit.base)}
          draft={describe({ name, aliases })}
          latest={edit.latest ? describe(edit.latest) : null}
          check={() => {
            void edit.check((signal) => getArtist(initial.id, signal))
          }}
          review={edit.review}
          back={cancel}
        />
      ) : (
        <>
          <h2 id="original-artist-title" className="text-lg font-bold">
            아티스트 정보 수정
          </h2>
          {initial && (
            <div className="song-read-value grid gap-2">
              <strong>이 아티스트를 쓰는 곳에 함께 반영돼요</strong>
              <p>
                이름·별칭 수정은 다른 원곡과 외부 참여자 표시에도 영향을 줄 수
                있습니다.
              </p>
            </div>
          )}
          {edit.notice && (
            <p role="status" className="text-sm text-warning">
              {edit.notice}
            </p>
          )}
          <ArtistFields
            value={{ name, aliases, alias, reason }}
            change={(patch) => {
              if (patch.name !== undefined) setName(patch.name)
              if (patch.aliases !== undefined) setAliases(patch.aliases)
              if (patch.alias !== undefined) setAlias(patch.alias)
              if (patch.reason !== undefined) setReason(patch.reason)
            }}
            errors={edit.errors}
            busy={edit.busy}
            dirty={dirty}
            action="아티스트 정보 저장"
            save={confirm}
            cancel={cancel}
          />
          <Hint>
            아티스트 정보 저장과 원곡 아티스트 연결 저장은 별개입니다.
          </Hint>
        </>
      )}
    </Dialog>
  )
}
