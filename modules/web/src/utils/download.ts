const invalidFileName = /[<>:"/\\|?*\u0000-\u001f]/g

export const safeFileName = (value: string, fallback = 'download') => {
  const normalized = value
    .replace(invalidFileName, '_')
    .replace(/[.\s]+$/g, '')
    .trim()
  return normalized.slice(0, 120) || fallback
}

export const saveBlob = (blob: Blob, fileName: string) => {
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = safeFileName(fileName)
  link.style.display = 'none'
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}

export const saveUtf8Text = (content: string, fileName: string) => {
  saveBlob(
    new Blob(['\ufeff', content], { type: 'text/plain;charset=utf-8' }),
    fileName,
  )
}

export const downloadImage = async (url: string, fileName: string) => {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 30_000)
  try {
    const response = await fetch(url, { signal: controller.signal })
    if (!response.ok) {
      throw new Error(`封面请求失败 (${response.status})`)
    }
    const blob = await response.blob()
    if (blob.type.startsWith('image/')) {
      saveBlob(blob, fileName)
      return
    }
    // 后端失败时返回 JSON（如 {isSuccess, errorMsg, data}），而不是图片
    let message = ''
    try {
      const text = await blob.text()
      const data = JSON.parse(text) as { errorMsg?: string } | null
      message = data?.errorMsg?.trim() || ''
    } catch {
      message = ''
    }
    throw new Error(message || '后端没有返回有效的封面图片')
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error('封面下载超时')
    }
    throw error
  } finally {
    window.clearTimeout(timer)
  }
}
