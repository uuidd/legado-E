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
  const response = await fetch(url)
  if (!response.ok) {
    throw new Error(`封面请求失败 (${response.status})`)
  }
  const blob = await response.blob()
  if (!blob.type.startsWith('image/')) {
    throw new Error('后端没有返回有效的封面图片')
  }
  saveBlob(blob, fileName)
}
