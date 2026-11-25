import { act, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Artwork } from './artwork'

describe('이미지 오류 복구', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('실패한 이미지만 지연 후 다시 요청하고 정상 이미지는 유지한다', () => {
    const { container } = render(
      <>
        <Artwork src="/failed.jpg" avatar />
        <Artwork src="/loaded.jpg" avatar />
      </>,
    )
    const [failed, loaded] = container.querySelectorAll('img')
    fireEvent.load(loaded!)
    fireEvent.error(failed!)
    expect(container).toHaveTextContent('♪')
    act(() => vi.advanceTimersByTime(999))
    expect(container.querySelectorAll('img')).toHaveLength(1)
    act(() => vi.advanceTimersByTime(1))
    const retried = container.querySelector('img[src="/failed.jpg"]')!
    expect(retried).not.toBe(failed)
    expect(retried).toHaveAttribute('src', '/failed.jpg')
    fireEvent.load(retried)
    act(() => vi.advanceTimersByTime(60000))
    expect(container.querySelector('img[src="/failed.jpg"]')).toBe(retried)
    expect(container.querySelector('img[src="/loaded.jpg"]')).toBe(loaded)
    expect(console.warn).toHaveBeenCalledTimes(1)
  })

  it('최대 두 번 재시도하고 실패하면 기본 표시를 유지한다', () => {
    const { container } = render(<Artwork src="/broken.jpg" avatar />)
    fireEvent.error(container.querySelector('img')!)
    act(() => vi.advanceTimersByTime(1000))
    fireEvent.error(container.querySelector('img')!)
    act(() => vi.advanceTimersByTime(1999))
    expect(container.querySelector('img')).toBeNull()
    act(() => vi.advanceTimersByTime(1))
    fireEvent.error(container.querySelector('img')!)
    act(() => vi.advanceTimersByTime(60000))
    expect(container.querySelector('img')).toBeNull()
    expect(container).toHaveTextContent('♪')
    expect(vi.getTimerCount()).toBe(0)
    expect(console.warn).toHaveBeenCalledTimes(3)
  })

  it('주소가 바뀌면 이전 재시도를 취소하고 새 이미지에 재시도 횟수를 적용한다', () => {
    const { container, rerender } = render(<Artwork src="/old.jpg" />)
    fireEvent.error(container.querySelector('img')!)
    rerender(<Artwork src="/new.jpg" />)
    expect(vi.getTimerCount()).toBe(0)
    const next = container.querySelector('img')!
    act(() => vi.advanceTimersByTime(1000))
    expect(container.querySelector('img')).toBe(next)
    expect(next).toHaveAttribute('src', '/new.jpg')
    fireEvent.error(next)
    act(() => vi.advanceTimersByTime(1000))
    expect(container.querySelector('img')).toHaveAttribute('src', '/new.jpg')
  })

  it('화면에서 제거되면 예약한 재시도를 취소한다', () => {
    const { container, unmount } = render(<Artwork src="/removed.jpg" />)
    fireEvent.error(container.querySelector('img')!)
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('주소가 없으면 요청하지 않고 지정한 기본 문구를 표시한다', () => {
    const { container } = render(
      <Artwork src={null} fallbackText="프로필 준비 중" />,
    )
    expect(container.querySelector('img')).toBeNull()
    expect(container).toHaveTextContent('프로필 준비 중')
    expect(vi.getTimerCount()).toBe(0)
  })
})
