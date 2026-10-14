import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './button'
import { TextField } from './input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs'

describe('공통 입력과 조작', () => {
  it('입력의 라벨·도움말·오류를 연결하고 기존 설명도 보존한다', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <TextField
        label="목록 이름"
        description="50자 이하"
        aria-describedby="external-hint"
      />,
    )
    const input = screen.getByRole('textbox', { name: '목록 이름' })
    await user.click(screen.getByText('목록 이름'))
    expect(input).toHaveFocus()
    await user.type(input, '내 노래')
    expect(input).toHaveValue('내 노래')
    const hintId = screen.getByText('50자 이하').id
    expect(input).toHaveAttribute('aria-describedby', `external-hint ${hintId}`)
    rerender(
      <TextField
        label="목록 이름"
        error="이름을 확인해 주세요"
        aria-describedby="external-hint"
      />,
    )
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('이름을 확인해 주세요').id).toBe(hintId)
  })

  it('키보드로 탭을 이동하고 비활성 탭은 건너뛴다', async () => {
    const user = userEvent.setup()
    render(
      <Tabs defaultValue="favorites">
        <TabsList aria-label="보관함 종류">
          <TabsTrigger value="favorites">즐겨찾기</TabsTrigger>
          <TabsTrigger value="disabled" disabled>
            사용 불가
          </TabsTrigger>
          <TabsTrigger value="playlists">플레이리스트</TabsTrigger>
        </TabsList>
        <TabsContent value="favorites">저장한 곡</TabsContent>
        <TabsContent value="playlists">내 목록</TabsContent>
      </Tabs>,
    )
    screen.getByRole('tab', { name: '즐겨찾기' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: '플레이리스트' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: '플레이리스트' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByRole('tabpanel')).toHaveTextContent('내 목록')
    await user.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: '즐겨찾기' })).toHaveFocus()
  })

  it('기본 버튼은 폼을 제출하지 않고 비활성 버튼은 실행하지 않는다', async () => {
    const user = userEvent.setup()
    const submit = vi.fn((e) => e.preventDefault())
    const clicked = vi.fn()
    render(
      <form onSubmit={submit}>
        <Button>취소</Button>
        <Button disabled onClick={clicked}>
          저장 중
        </Button>
      </form>,
    )
    await user.click(screen.getByRole('button', { name: '취소' }))
    await user.click(screen.getByRole('button', { name: '저장 중' }))
    expect(submit).not.toHaveBeenCalled()
    expect(clicked).not.toHaveBeenCalled()
    fireEvent.submit(
      screen.getByRole('button', { name: '취소' }).closest('form')!,
    )
    expect(submit).toHaveBeenCalledTimes(1)
  })
})
