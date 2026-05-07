// admin/src/hooks/useKbList.ts
import { useQuery } from '@tanstack/react-query'
import { listKbs } from '../api/knowledge-base'

export const KB_LIST_KEY = ['kb-list'] as const

export function useKbList() {
  return useQuery({
    queryKey: KB_LIST_KEY,
    queryFn: () => listKbs(0, 50),
  })
}
