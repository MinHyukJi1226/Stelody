import { Button } from '@/shared/ui/button'

export function CatalogExit({
  onContinue,
  onLeave,
  uncertain = false,
}: {
  onContinue: () => void
  onLeave: () => void
  uncertain?: boolean
}) {
  return (
    <div className="grid gap-6">
      <h2 id="original-artist-title" className="text-lg font-bold">
        {uncertain
          ? '저장 결과를 확인하지 않고 나갈까요?'
          : '편집을 그만둘까요?'}
      </h2>
      <p className="text-sm text-muted">
        {uncertain
          ? '이미 반영됐을 수 있습니다. 요청의 성공 여부를 확인하지 않고 나갑니다.'
          : '아직 저장하지 않은 선택·입력은 버려집니다. 이미 등록한 아티스트와 저장한 원곡 정보는 유지됩니다.'}
      </p>
      <div className="flex flex-wrap gap-2">
        <Button onClick={onContinue}>계속 편집</Button>
        <Button variant="destructive" onClick={onLeave}>
          저장하지 않고 돌아가기
        </Button>
      </div>
    </div>
  )
}
