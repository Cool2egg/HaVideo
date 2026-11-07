import SparkMD5 from 'spark-md5'
import { api } from './api'

const CHUNK_SIZE = 8 * 1024 * 1024
const HASH_CHUNK = 4 * 1024 * 1024
const CONCURRENCY = 3

export async function uploadVideo(file, { onProgress, signal } = {}) {
  const contentHash = await computeMd5(file, percent => {
    onProgress?.({ phase: 'hash', percent })
  })
  throwIfAborted(signal)

  const totalParts = Math.ceil(file.size / CHUNK_SIZE)
  const init = await api('/upload/init', {
    method: 'POST',
    body: {
      filename: file.name,
      size: file.size,
      contentHash,
      totalParts,
      chunkSize: CHUNK_SIZE
    }
  })

  if (init.dedup && !init.uploadId) {
    onProgress?.({ phase: 'done', percent: 100, dedup: true })
    return { mediaId: init.mediaId, dedup: true }
  }

  const uploaded = new Set(init.uploadedParts || [])
  const missing = []
  for (let part = 1; part <= totalParts; part++) {
    if (!uploaded.has(part)) missing.push(part)
  }

  let finished = uploaded.size
  onProgress?.({
    phase: 'upload',
    percent: Math.round((finished / totalParts) * 100),
    done: finished,
    total: totalParts
  })

  let cursor = 0
  const worker = async () => {
    while (true) {
      const index = cursor++
      if (index >= missing.length) return
      throwIfAborted(signal)
      const part = missing[index]
      const start = (part - 1) * CHUNK_SIZE
      const end = Math.min(file.size, start + CHUNK_SIZE)
      const form = new FormData()
      form.append('file', file.slice(start, end), `chunk-${part}`)
      await api(`/upload/${init.uploadId}/parts/${part}`, { method: 'PUT', body: form })
      finished++
      onProgress?.({
        phase: 'upload',
        percent: Math.round((finished / totalParts) * 100),
        done: finished,
        total: totalParts
      })
    }
  }

  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, missing.length) }, worker))
  throwIfAborted(signal)

  onProgress?.({ phase: 'merge', percent: 100 })
  const completed = await api(`/upload/${init.uploadId}/complete`, { method: 'POST' })
  onProgress?.({ phase: 'done', percent: 100 })
  return { mediaId: completed.mediaId, dedup: init.dedup }
}

async function computeMd5(file, onProgress) {
  const spark = new SparkMD5.ArrayBuffer()
  const total = Math.max(1, Math.ceil(file.size / HASH_CHUNK))
  for (let index = 0; index < total; index++) {
    const start = index * HASH_CHUNK
    const end = Math.min(file.size, start + HASH_CHUNK)
    const buffer = await file.slice(start, end).arrayBuffer()
    spark.append(buffer)
    onProgress?.(Math.round(((index + 1) / total) * 100))
  }
  return spark.end()
}

function throwIfAborted(signal) {
  if (signal?.aborted) {
    const error = new Error('上传已取消')
    error.name = 'AbortError'
    throw error
  }
}
