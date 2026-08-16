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
          <span v-if="catalog.length"
            >{{ completed }}/{{ catalog.length }}</span
          >
        </div>
      </template>

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
      <el-button
        v-else-if="
          status === 'ready' || status === 'failed' || status === 'cancelled'
        "
        type="primary"
        :icon="Download"
        :disabled="catalog.length === 0"
        @click="startDownload"
      >
        {{ status === 'ready' ? '开始下载' : '重新下载' }}
      </el-button>
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
import { Close, Download, Loading } from '@element-plus/icons-vue'

type DownloadStatus =
  'loading' | 'ready' | 'downloading' | 'completed' | 'cancelled' | 'failed'

type FailedChapter = {
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
let taskId = 0
let cancelRequested = false

const percentage = computed(() =>
  catalog.value.length
    ? Math.round((completed.value / catalog.value.length) * 100)
    : 0,
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

const prepare = async () => {
  const book = props.book
  if (!book) return
  const currentTask = ++taskId
  cancelRequested = true
  catalog.value = []
  completed.value = 0
  failures.value = []
  errorMessage.value = ''
  status.value = 'loading'
  try {
    const response = await API.getChapterList(book.bookUrl)
    if (currentTask !== taskId) return
    if (!response.data.isSuccess) throw new Error(response.data.errorMsg)
    catalog.value = response.data.data
    status.value = 'ready'
  } catch (error) {
    if (currentTask !== taskId) return
    status.value = 'failed'
    errorMessage.value = error instanceof Error ? error.message : '读取目录失败'
  }
}

const getChapterContent = async (bookUrl: string, chapter: BookChapter) => {
  let lastError: unknown
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const response = await API.getBookContent(bookUrl, chapter.index)
      if (!response.data.isSuccess) throw new Error(response.data.errorMsg)
      return response.data.data
    } catch (error) {
      lastError = error
      if (attempt === 0) {
        await new Promise(resolve => window.setTimeout(resolve, 400))
      }
    }
  }
  throw lastError
}

const startDownload = async () => {
  const book = props.book
  if (!book || catalog.value.length === 0) return
  const currentTask = ++taskId
  cancelRequested = false
  completed.value = 0
  failures.value = []
  errorMessage.value = ''
  status.value = 'downloading'
  const chapterParts = new Array<string>(catalog.value.length)
  let nextPosition = 0

  const worker = async () => {
    while (!cancelRequested && currentTask === taskId) {
      const position = nextPosition++
      if (position >= catalog.value.length) return
      const chapter = catalog.value[position]
      if (chapter.isVolume) {
        chapterParts[position] = `\n\n${chapter.title}\n`
        completed.value++
        continue
      }
      try {
        const content = await getChapterContent(book.bookUrl, chapter)
        chapterParts[position] = `\n\n${chapter.title}\n\n${content.trim()}\n`
      } catch (error) {
        const message = error instanceof Error ? error.message : '未知错误'
        failures.value.push({ title: chapter.title, message })
        chapterParts[position] =
          `\n\n${chapter.title}\n\n[本章下载失败：${message}]\n`
      } finally {
        completed.value++
      }
    }
  }

  try {
    const workerCount = Math.min(4, catalog.value.length)
    await Promise.all(Array.from({ length: workerCount }, worker))
    if (cancelRequested || currentTask !== taskId) {
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
    ElMessage.success(`小说已保存为 ${fileName}`)
  } catch (error) {
    status.value = 'failed'
    errorMessage.value = error instanceof Error ? error.message : '小说下载失败'
  }
}

const cancelDownload = () => {
  cancelRequested = true
  taskId++
  status.value = 'cancelled'
}

const beforeClose = (done: () => void) => {
  if (status.value !== 'downloading') {
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
    if (open) prepare()
    else if (status.value === 'downloading') cancelDownload()
  },
)

onBeforeUnmount(() => {
  cancelRequested = true
  taskId++
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
</style>
