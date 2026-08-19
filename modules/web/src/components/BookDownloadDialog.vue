<template>
  <el-dialog
    v-model="visible"
    width="min(560px, calc(100vw - 32px))"
    :close-on-click-modal="status !== 'downloading'"
    :close-on-press-escape="status !== 'downloading'"
    :before-close="beforeClose"
    title="下载小说"
  >
    <div v-if="book" class="download-body">
      <div class="book-summary">
        <div class="book-name">{{ book.name }}</div>
        <div class="book-author">{{ book.author || '未知作者' }}</div>
      </div>

      <div v-if="status === 'loading'" class="loading-catalog">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在读取目录</span>
      </div>

      <template v-else>
        <el-progress
          :percentage="percentage"
          :status="progressStatus"
          :stroke-width="10"
        />
        <div class="download-stats">
          <span>{{ statusText }}</span>
          <span v-if="runTotal">{{ completed }}/{{ runTotal }}</span>
        </div>
      </template>

      <div
        v-if="status === 'ready' || (status === 'failed' && catalog.length)"
        class="download-options"
      >
        <div class="option-item">
          <span class="option-label">并发数</span>
          <el-input-number
            v-model="concurrency"
            :min="1"
            :max="8"
            size="small"
            style="width: 110px"
          />
        </div>
        <el-button
          text
          :icon="RefreshRight"
          @click="prepare(true)"
        >
          刷新目录
        </el-button>
      </div>

      <el-alert
        v-if="status === 'ready'"
        title="正文会从阅读 App 获取，完成后由浏览器保存为 UTF-8 TXT。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="failures.length"
        :title="`${failures.length} 个章节下载失败，TXT 中已保留失败标记。`"
        type="warning"
        :closable="false"
        show-icon
      />
    </div>

    <template #footer>
      <el-button
        v-if="status === 'downloading'"
        :icon="Close"
        @click="cancelDownload"
      >
        取消任务
      </el-button>
      <template v-else>
        <el-button
          v-if="canRetryCatalog"
          type="primary"
          :icon="RefreshRight"
          @click="prepare(true)"
        >
          重试读取目录
        </el-button>
        <el-button
          v-if="canRetryFailed"
          type="primary"
          :icon="RefreshRight"
          @click="startDownload(true)"
        >
          仅重试失败章节
        </el-button>
        <el-button
          v-if="canDownload"
          :type="canRetryFailed ? 'default' : 'primary'"
          :icon="Download"
          @click="startDownload(false)"
        >
          {{ status === 'ready' ? '开始下载' : '重新下载' }}
        </el-button>
      </template>
      <el-button v-if="status !== 'downloading'" @click="visible = false">
        关闭
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { Book, BookChapter } from '@/book'
import API from '@api'
import { saveUtf8Text, safeFileName } from '@/utils/download'
import {
  Close,
  Download,
  Loading,
  RefreshRight,
} from '@element-plus/icons-vue'

type DownloadStatus =
  | 'loading'
  | 'ready'
  | 'downloading'
  | 'completed'
  | 'cancelled'
  | 'failed'

type FailedChapter = {
  index: number
  title: string
  message: string
}

const props = defineProps<{
  modelValue: boolean
  book: Book | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

const catalog = ref<BookChapter[]>([])
const status = ref<DownloadStatus>('loading')
const completed = ref(0)
const failures = ref<FailedChapter[]>([])
const errorMessage = ref('')
const concurrency = ref(4)
const runTotal = ref(0)

let taskId = 0
let cancelRequested = false
let abortController: AbortController | null = null
let chapterParts: string[] = []

const percentage = computed(() =>
  runTotal.value ? Math.round((completed.value / runTotal.value) * 100) : 0,
)

const progressStatus = computed(() => {
  if (status.value === 'completed' && failures.value.length === 0)
    return 'success'
  if (status.value === 'failed') return 'exception'
  return undefined
})

const statusText = computed(() => {
  switch (status.value) {
    case 'ready':
      return `目录已就绪，共 ${catalog.value.length} 项`
    case 'downloading':
      return '正在获取正文'
    case 'completed':
      return failures.value.length ? '下载完成，部分章节失败' : '下载完成'
    case 'cancelled':
      return '任务已取消'
    case 'failed':
      return '下载失败'
    default:
      return ''
  }
})

const canRetryCatalog = computed(
  () => status.value === 'failed' && catalog.value.length === 0,
)
const canRetryFailed = computed(
  () =>
    failures.value.length > 0 &&
    catalog.value.length > 0 &&
    status.value !== 'loading' &&
    status.value !== 'downloading',
)
const canDownload = computed(
  () =>
    catalog.value.length > 0 &&
    (status.value === 'ready' ||
      status.value === 'failed' ||
      status.value === 'cancelled' ||
      status.value === 'completed'),
)

const truncateErrorMessage = (message: unknown, max = 200) => {
  const value = String(message ?? '')
    .trim()
    .replace(/\s+/g, ' ')
  return value.length > max ? `${value.slice(0, max)}…` : value
}

const formatError = (error: unknown, fallback: string) =>
  truncateErrorMessage(error instanceof Error ? error.message : '') || fallback

const getChapterContent = async (
  bookUrl: string,
  chapterIndex: number,
  signal?: AbortSignal,
) => {
  let lastError: unknown
  for (let attempt = 0; attempt < 2; attempt++) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
    try {
      const response = await API.getBookContent(bookUrl, chapterIndex, signal)
      if (!response.data.isSuccess) throw new Error(response.data.errorMsg)
      return response.data.data
    } catch (error) {
      if (signal?.aborted) throw error
      lastError = error
      if (attempt === 0) {
        await new Promise(resolve => window.setTimeout(resolve, 400))
      }
    }
  }
  throw lastError
}

const prepare = async (refresh = false) => {
  const book = props.book
  if (!book) return
  abortController?.abort()
  const currentTask = ++taskId
  cancelRequested = true
  catalog.value = []
  completed.value = 0
  failures.value = []
  errorMessage.value = ''
  runTotal.value = 0
  status.value = 'loading'

  const controller = new AbortController()
  abortController = controller
  try {
    const response = refresh
      ? await API.refreshToc(book.bookUrl, controller.signal)
      : await API.getChapterList(book.bookUrl, controller.signal)
    if (currentTask !== taskId || controller.signal.aborted) return
    if (!response.data.isSuccess)
      throw new Error(truncateErrorMessage(response.data.errorMsg))
    catalog.value = response.data.data
    runTotal.value = catalog.value.length
    if (catalog.value.length === 0) {
      status.value = 'failed'
      errorMessage.value = '该书籍没有可用章节，请点击“刷新目录”重试'
      return
    }
    status.value = 'ready'
  } catch (error) {
    if (currentTask !== taskId || controller.signal.aborted) return
    status.value = 'failed'
    errorMessage.value = formatError(error, '读取目录失败')
  }
}

const setFailure = (index: number, title: string, message: string) => {
  const existing = failures.value.find(item => item.index === index)
  if (existing) {
    existing.message = message
  } else {
    failures.value.push({ index, title, message })
  }
}

const removeFailure = (index: number) => {
  failures.value = failures.value.filter(item => item.index !== index)
}

const startDownload = async (retryOnly = false) => {
  const book = props.book
  if (!book || catalog.value.length === 0) return

  const failedIndexes = failures.value
    .map(item => item.index)
    .filter(index => index >= 0 && index < catalog.value.length)
  if (retryOnly && failedIndexes.length === 0) return

  abortController?.abort()
  const currentTask = ++taskId
  cancelRequested = false
  errorMessage.value = ''
  status.value = 'downloading'

  const controller = new AbortController()
  abortController = controller

  const targets = retryOnly
    ? failedIndexes
    : catalog.value.map((_, index) => index)
  runTotal.value = targets.length
  completed.value = 0

  if (!retryOnly) {
    failures.value = []
    chapterParts = new Array(catalog.value.length)
  }

  let nextPosition = 0
  const worker = async () => {
    while (!cancelRequested && currentTask === taskId) {
      const position = nextPosition++
      if (position >= targets.length) return
      const chapterIndex = targets[position]
      const chapter = catalog.value[chapterIndex]

      if (chapter.isVolume) {
        chapterParts[chapterIndex] = `\n\n${chapter.title}\n`
        completed.value++
        continue
      }

      try {
        const content = await getChapterContent(
          book.bookUrl,
          chapter.index,
          controller.signal,
        )
        chapterParts[chapterIndex] = `\n\n${chapter.title}\n\n${content.trim()}\n`
        if (retryOnly) removeFailure(chapterIndex)
      } catch (error) {
        if (controller.signal.aborted) return
        const message = formatError(error, '未知错误')
        setFailure(chapterIndex, chapter.title, message)
        chapterParts[chapterIndex] =
          `\n\n${chapter.title}\n\n[本章下载失败：${message}]\n`
      } finally {
        if (!controller.signal.aborted) completed.value++
      }
    }
  }

  try {
    const workerCount = Math.min(concurrency.value, targets.length)
    await Promise.all(Array.from({ length: workerCount }, worker))
    if (cancelRequested || currentTask !== taskId || controller.signal.aborted) {
      status.value = 'cancelled'
      return
    }
    const header = `${book.name}\n作者：${book.author || '未知'}\n`
    const fileName = safeFileName(
      `${book.name}-${book.author || '未知作者'}.txt`,
      'novel.txt',
    )
    saveUtf8Text(header + chapterParts.join(''), fileName)
    status.value = 'completed'
    if (failures.value.length) {
      ElMessage.warning(
        `小说已保存为 ${fileName}，仍有 ${failures.value.length} 个章节失败`,
      )
    } else {
      ElMessage.success(`小说已保存为 ${fileName}`)
    }
  } catch (error) {
    status.value = 'failed'
    errorMessage.value = formatError(error, '小说下载失败')
  } finally {
    if (currentTask === taskId) abortController = null
  }
}

const cancelDownload = () => {
  cancelRequested = true
  taskId++
  abortController?.abort()
  status.value = 'cancelled'
}

const beforeClose = (done: () => void) => {
  if (status.value !== 'downloading') {
    abortController?.abort()
    done()
    return
  }
  ElMessageBox.confirm('关闭窗口会取消当前下载任务。', '取消下载', {
    confirmButtonText: '取消任务',
    cancelButtonText: '继续下载',
    type: 'warning',
  }).then(() => {
    cancelDownload()
    done()
  })
}

watch(
  () => props.modelValue,
  open => {
    if (open) {
      prepare()
    } else {
      if (status.value === 'downloading') cancelDownload()
      abortController?.abort()
    }
  },
)

onBeforeUnmount(() => {
  cancelRequested = true
  taskId++
  abortController?.abort()
})
</script>

<style scoped lang="scss">
.download-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 190px;
}

.book-summary {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.book-name {
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
}

.book-author {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.loading-catalog {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 90px;
  color: var(--el-text-color-secondary);
}

.download-stats {
  display: flex;
  justify-content: space-between;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.download-options {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .option-item {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .option-label {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}
</style>