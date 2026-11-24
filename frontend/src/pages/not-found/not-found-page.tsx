import { PageState } from '@/shared/ui/page-state'
import { StatePageLayout } from '@/shared/ui/state-page-layout'

export function NotFoundPage() {
  return (
    <StatePageLayout>
      <PageState
        heading="h1"
        kind="unavailable"
        title="페이지를 찾을 수 없습니다"
        action={{ to: '/', label: '홈으로 돌아가기' }}
      />
    </StatePageLayout>
  )
}
