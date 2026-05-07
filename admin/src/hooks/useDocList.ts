// admin/src/hooks/useDocList.ts
import { useQuery } from '@tanstack/react-query'
import { listDocuments } from '../api/document'
import type { DocStatus } from '../types/api'

const ACTIVE_STATUSES: DocStatus[] = ['PENDING', 'PROCESSING']

export const docListKey = (kbId: string) => ['docs', kbId] as const

export function useDocList(kbId: string) {
  return useQuery({
    queryKey: docListKey(kbId),
    queryFn: () => listDocuments(kbId),
    enabled: !!kbId,
    refetchInterval: query => {
      const docs = query.state.data?.content ?? []
      const hasActive = docs.some(d => ACTIVE_STATUSES.includes(d.status))
      return hasActive ? 3000 : false
    },
  })
}
