// admin/src/test/api.client.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { request, streamRequest } from '../api/client'

const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

beforeEach(() => mockFetch.mockReset())

describe('request', () => {
  it('returns parsed JSON on 200', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ id: '1', name: 'KB' }),
    })
    const result = await request('/knowledge-bases/1')
    expect(result).toEqual({ id: '1', name: 'KB' })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/knowledge-bases/1',
      expect.objectContaining({}),
    )
  })

  it('returns undefined on 204', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, status: 204 })
    const result = await request('/knowledge-bases/1', { method: 'DELETE' })
    expect(result).toBeUndefined()
  })

  it('throws Error with server message on 4xx', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      text: async () => 'KnowledgeBase not found: abc',
    })
    await expect(request('/knowledge-bases/abc')).rejects.toThrow(
      'KnowledgeBase not found: abc',
    )
  })
})

describe('streamRequest', () => {
  it('throws on non-ok response', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      body: null,
      text: async () => 'Bad Request',
    })
    await expect(streamRequest('/chat', {})).rejects.toThrow('Bad Request')
  })
})
