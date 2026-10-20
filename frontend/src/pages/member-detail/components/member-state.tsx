import { PageState } from '@/shared/ui/page-state'
import type { ComponentProps } from 'react'

export function MemberState(
  props: Omit<ComponentProps<typeof PageState>, 'action'>,
) {
  return (
    <PageState
      {...props}
      action={
        props.kind === 'empty'
          ? { to: '/search', label: '노래 찾기' }
          : { to: '/members', label: '모든 멤버' }
      }
    />
  )
}
