import { useCallback } from 'react'
import {
  DndContext,
  closestCenter,
  MouseSensor,
  TouchSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import {
  SortableContext,
  useSortable,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import type { MemberCard } from '@/shared/api/types'
import type { PlaylistItem } from '@/features/playlists/api'
import { Button } from '@/shared/ui/button'
import { PlaylistSong } from './playlist-song'

export function PlaylistSortableItems({
  items,
  members,
  disabled,
  removalLocked,
  onMove,
  onRemove,
}: {
  items: PlaylistItem[]
  members: readonly MemberCard[] | undefined
  disabled: boolean
  removalLocked: boolean
  onMove: (from: number, to: number) => void
  onRemove: (item: PlaylistItem) => void
}) {
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 250, tolerance: 5 },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  )
  const label = (id: string | number) =>
    items.find((i) => i.id === id)?.song?.title || '이용 불가 곡'
  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      accessibility={{
        screenReaderInstructions: {
          draggable:
            '스페이스 키로 곡을 잡고 위아래 방향키로 이동하세요. 스페이스 키로 놓거나 Escape 키로 취소할 수 있습니다.',
        },
        announcements: {
          onDragStart: ({ active }) => `${label(active.id)} 이동을 시작합니다.`,
          onDragOver: ({ active, over }) =>
            over
              ? `${label(active.id)}, ${items.findIndex((i) => i.id === over.id) + 1}번째 위치입니다.`
              : undefined,
          onDragEnd: ({ active }) =>
            `${label(active.id)} 이동을 마쳤습니다. 변경 순서를 저장해 주세요.`,
          onDragCancel: () => '이동을 취소했습니다.',
        },
      }}
      onDragEnd={({ active, over }) => {
        if (disabled || !over || active.id === over.id) return
        onMove(
          items.findIndex((i) => i.id === active.id),
          items.findIndex((i) => i.id === over.id),
        )
      }}
    >
      <SortableContext
        items={items.map((i) => i.id)}
        strategy={verticalListSortingStrategy}
      >
        <div
          role="list"
          aria-label="플레이리스트 곡 순서"
          className="flex min-w-0 flex-col gap-8"
        >
          {items.map((item, index) => (
            <SortableItem
              key={item.id}
              item={item}
              index={index}
              count={items.length}
              members={members}
              disabled={disabled}
              removalLocked={removalLocked}
              onMove={onMove}
              onRemove={onRemove}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  )
}

function SortableItem({
  item,
  index,
  count,
  members,
  disabled,
  removalLocked,
  onMove,
  onRemove,
}: {
  item: PlaylistItem
  index: number
  count: number
  members: readonly MemberCard[] | undefined
  disabled: boolean
  removalLocked: boolean
  onMove: (from: number, to: number) => void
  onRemove: (item: PlaylistItem) => void
}) {
  const {
    setNodeRef,
    setActivatorNodeRef,
    transform,
    transition,
    attributes,
    listeners,
    isDragging,
  } = useSortable({ id: item.id, disabled })
  const setRef = useCallback(
    (element: HTMLElement | null) => {
      setNodeRef(element)
      setActivatorNodeRef(element)
    },
    [setNodeRef, setActivatorNodeRef],
  )
  const available = item.available && item.song
  return (
    <article
      ref={setRef}
      role="listitem"
      tabIndex={disabled ? -1 : 0}
      aria-label={`${available ? item.song!.title : '이용 불가 곡'} 순서 변경`}
      aria-roledescription="순서 변경 가능한 곡"
      aria-describedby={attributes['aria-describedby']}
      aria-disabled={disabled}
      {...listeners}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        position: 'relative',
        zIndex: isDragging ? 1 : undefined,
      }}
      className={`flex min-w-0 touch-manipulation flex-col gap-4 rounded-2xl p-4 ${available ? 'bg-surface md:flex-row md:items-center md:gap-3' : 'bg-bg'} ${isDragging ? 'opacity-70 shadow-lg' : ''} ${disabled ? '' : 'cursor-grab active:cursor-grabbing'}`}
    >
      {available ? (
        <PlaylistSong song={item.song!} members={members} />
      ) : (
        <div className="flex min-w-0 flex-col gap-1">
          <h2 className="text-sm leading-[1.5]">현재 이용할 수 없는 곡</h2>
          <p className="text-xs leading-[1.5] text-muted">
            {item.unavailableMessage || '저장한 순서는 유지됩니다.'}
          </p>
        </div>
      )}
      <div
        className="flex shrink-0 flex-wrap gap-2"
        onMouseDown={(e) => e.stopPropagation()}
        onTouchStart={(e) => e.stopPropagation()}
      >
        {available && (
          <>
            <Button
              disabled={disabled || index === 0}
              aria-label={`${item.song!.title} 위로 이동`}
              onClick={() => onMove(index, index - 1)}
            >
              ↑ 위로
            </Button>
            <Button
              disabled={disabled || index === count - 1}
              aria-label={`${item.song!.title} 아래로 이동`}
              onClick={() => onMove(index, index + 1)}
            >
              ↓ 아래로
            </Button>
          </>
        )}
        <Button
          disabled={disabled || removalLocked}
          aria-label={`${available ? item.song!.title : '이용 불가 곡'} 목록에서 제거`}
          onClick={() => onRemove(item)}
        >
          {available ? '제거' : '목록에서 제거'}
        </Button>
      </div>
    </article>
  )
}
