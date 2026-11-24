import { PageState } from '@/shared/ui/page-state'
import { StatePageLayout } from '@/shared/ui/state-page-layout'

export function RouteErrorPage() {
  return (
    <StatePageLayout>
      <PageState
        heading="h1"
        kind="error"
        title="화면을 불러오지 못했습니다"
        description="잠시 후 다시 시도해 주세요."
        onRetry={() => window.location.reload()}
        action={{ to: '/', label: '홈으로' }}
      />
    </StatePageLayout>
  )
}
