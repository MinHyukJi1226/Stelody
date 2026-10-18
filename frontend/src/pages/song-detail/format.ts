export const formatDate = (value: string) =>
  new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(new Date(value))
    .replace(/\. /g, '.')
    .replace(/\.$/, '')

export const formatObservedAt = (value: string | null) =>
  value
    ? new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
      }).format(new Date(value)) + ' KST'
    : '미수집'

export { externalUrl } from '@/shared/lib/external-url'

export const youtubeIdValid = (value: string) => /^[\w-]{11}$/.test(value)
