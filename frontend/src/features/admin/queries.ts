import { queryOptions } from '@tanstack/react-query'
import {
  getCollection,
  getFailures,
  getInbox,
  getRegistrations,
  getReviews,
  getSpecialReviews,
  type FailureWindow,
} from './api'

export const adminKeys = (user: string) => ['private', user, 'admin'] as const
const options = <T>(
  user: string,
  key: readonly unknown[],
  read: (signal: AbortSignal) => Promise<T>,
) =>
  queryOptions({
    queryKey: [...adminKeys(user), ...key],
    queryFn: async ({ signal }) => {
      const result = await read(signal)
      signal.throwIfAborted()
      return result
    },
    retry: false,
    staleTime: 0,
    gcTime: 0,
  })
export const adminInboxOptions = (user: string) =>
  options(user, ['inbox'], async (signal) => {
    const [inbox, collection] = await Promise.all([
      getInbox(signal),
      getCollection(signal),
    ])
    return { inbox, collection }
  })
export const adminWindowOptions = (user: string) =>
  options(user, ['failure-window'], getInbox)
export const adminCollectionOptions = (user: string) =>
  options(user, ['collection'], getCollection)
export const adminReviewOptions = (user: string, page: number) =>
  options(user, ['reviews', page], (signal) => getReviews(page, signal))
export const adminRegistrationOptions = (user: string, page: number) =>
  options(user, ['registrations', page], (signal) =>
    getRegistrations(page, signal),
  )
export const adminSpecialOptions = (user: string, page: number) =>
  options(user, ['special', page], (signal) => getSpecialReviews(page, signal))
export const adminFailureOptions = (
  user: string,
  page: number,
  window: FailureWindow,
) =>
  options(user, ['failures', window.from, window.to, page], (signal) =>
    getFailures(page, window, signal),
  )
