import { Form } from 'react-router'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'

export function SearchHero() {
  return (
    <section
      className="home-hero flex flex-col gap-4 rounded-2xl bg-night p-6 text-on-accent md:px-10 md:py-12"
      aria-labelledby="home-title"
    >
      <p className="text-sm leading-[1.5]">Stelody</p>
      <h1 id="home-title" className="text-[32px] font-bold leading-[1.5]">
        좋아하는 목소리,
        <br />
        오래 듣고 싶은 노래.
      </h1>
      <p className="text-sm leading-[1.5]">
        곡명부터 원곡, 멤버까지 한 번에 찾아보세요.
      </p>
      <Form
        action="/search"
        role="search"
        aria-label="노래 검색"
        className="flex min-w-0 items-center gap-2"
      >
        <label htmlFor="home-search" className="sr-only">
          곡명, 원곡, 멤버 검색
        </label>
        <Input
          id="home-search"
          name="q"
          type="search"
          maxLength={200}
          placeholder="곡명, 원곡, 멤버 검색"
          className="min-h-[53px] flex-1 border-0 text-sm leading-[1.5]"
          required
          pattern={'.*\\S.*'}
        />
        <Button variant="primary" type="submit">
          검색
        </Button>
      </Form>
    </section>
  )
}
