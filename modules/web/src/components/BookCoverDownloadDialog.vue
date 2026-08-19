<template>
  <el-dialog
    v-model="visible"
    class="cover-download-dialog"
    width="min(760px, calc(100vw - 32px))"
    :close-on-click-modal="false"
    title="下载封面"
  >
    <div v-if="book" class="cover-dialog-body">
      <div class="book-heading">
        <div>
          <div class="book-name">{{ book.name }}</div>
          <div class="book-author">{{ book.author || '未知作者' }}</div>
        </div>
        <div class="search-status">
          <el-icon v-if="searching" class="is-loading"><Loading /></el-icon>
          <span v-if="searching">已检查 {{ processedSources }} 个书源</span>
          <span v-else-if="canResumeSearch"
            >已找到 {{ candidates.length }} 张封面</span
          >
          <span v-else>找到 {{ candidates.length }} 张封面</span>
        </div>
      </div>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />

      <div v-if="candidates.length" class="cover-grid">
        <div
          v-for="(candidate, index) in candidates"
          :key="candidate.key"
          class="cover-result"
        >
          <el-image
            class="cover-preview"
            :src="thumbnailUrl(candidate)"
            fit="cover"
            loading="lazy"
            role="button"
            tabindex="0"
            :aria-label="`查看 ${candidate.originName} 的封面`"
            @click="openPreview(index)"
            @keydown.enter.prevent="openPreview(index)"
            @keydown.space.prevent="openPreview(index)"
          >
            <template #error>
              <div class="cover-error">
                <el-icon><Picture /></el-icon>
              </div>
            </template>
          </el-image>
          <div class="cover-meta">
            <span :title="candidate.originName">{{
              candidate.originName
            }}</span>
            <el-button
              text
              :icon="Download"
              :loading="downloadingKey === candidate.key"
              :disabled="Boolean(downloadingKey)"
              @click="downloadCandidate(candidate)"
            >
              下载
            </el-button>
          </div>
        </div>
      </div>

      <el-empty
        v-else-if="!searching"
        description="没有找到可下载的封面"
        :image-size="72"
      />
      <div v-else class="cover-skeletons" aria-label="正在搜索封面">
        <el-skeleton v-for="index in 4" :key="index" animated>
          <template #template>
            <el-skeleton-item variant="image" class="skeleton-image" />
            <el-skeleton-item variant="text" class="skeleton-text" />
          </template>
        </el-skeleton>
      </div>
    </div>

    <el-image-viewer
      v-if="previewIndex !== null"
      :url-list="previewUrls"
      :initial-index="previewIndex"
      :hide-on-click-modal="false"
      :teleported="true"
      @close="previewIndex = null"
      @switch="previewIndex = $event"
    />

    <template #footer>
      <el-button
        :icon="Refresh"
        :loading="searching"
        @click="startOrResumeSearch"
      >
        {{ canResumeSearch ? '继续搜索书源' : '重新搜索' }}
      </el-button>
      <el-button type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { Book, SeachBook } from '@/book'
import API from '@api'
import { downloadImage, safeFileName } from '@/utils/download'
import { Download, Loading, Picture, Refresh } from '@element-plus/icons-vue'

type CoverCandidate = {
  key: string
  bookUrl: string
  coverUrl: string
  origin?: string
  originName: string
  originOrder: number
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
const candidates = ref<CoverCandidate[]>([])
const previewIndex = ref<number | null>(null)
const searching = ref(false)
const canResumeSearch = ref(false)
const processedSources = ref(0)
const errorMessage = ref('')
const downloadingKey = ref('')
let stopSearch: (() => void) | undefined
let searchId = 0

const addCandidates = (items: SeachBook[]) => {
  const knownBookUrls = new Set(candidates.value.map(item => item.bookUrl))
  items.forEach(item => {
    if (!item.coverUrl || knownBookUrls.has(item.bookUrl)) return
    knownBookUrls.add(item.bookUrl)
    candidates.value.push({
      key: item.bookUrl || `cover-rule:${item.coverUrl}`,
      bookUrl: item.bookUrl,
      coverUrl: item.coverUrl,
      origin: item.origin,
      originName: item.originName || '未知书源',
      originOrder: item.originOrder,
    })
  })
  candidates.value.sort((left, right) => left.originOrder - right.originOrder)
}

const startSearch = (searchSourcesOnly = false) => {
  const book = props.book
  if (!book) return
  stopSearch?.()
  const currentSearchId = ++searchId
  if (!searchSourcesOnly) {
    candidates.value = []
    const currentCover = book.customCoverUrl || book.coverUrl
    if (currentCover) {
      candidates.value.push({
        key: 'current-cover',
        bookUrl: 'current-cover',
        coverUrl: currentCover,
        origin: book.origin,
        originName: '当前封面',
        originOrder: Number.MIN_SAFE_INTEGER,
      })
    }
  }
  processedSources.value = 0
  errorMessage.value = ''
  canResumeSearch.value = false
  searching.value = true
  stopSearch = API.searchCover(
    book.name,
    book.author,
    {
      onResult: items => {
        if (currentSearchId === searchId) addCandidates(items)
      },
      onProgress: () => {
        if (currentSearchId === searchId) processedSources.value++
      },
      onPaused: () => {
        if (currentSearchId === searchId) canResumeSearch.value = true
      },
      onError: message => {
        if (currentSearchId === searchId) errorMessage.value = message
      },
      onFinish: () => {
        if (currentSearchId === searchId) searching.value = false
      },
    },
    searchSourcesOnly,
  )
}

const startOrResumeSearch = () => startSearch(canResumeSearch.value)

const thumbnailUrl = (candidate: CoverCandidate) =>
  API.getProxyCoverUrl(candidate.coverUrl, candidate.origin)

const previewUrls = computed(() => candidates.value.map(thumbnailUrl))

const openPreview = (index: number) => {
  previewIndex.value = index
}

const downloadCandidate = async (candidate: CoverCandidate) => {
  const book = props.book
  if (!book) return
  downloadingKey.value = candidate.key
  try {
    const fileName = safeFileName(
      `${book.name}-${book.author || '未知作者'}-${candidate.originName}.png`,
      'cover.png',
    )
    await downloadImage(
      API.getOriginalCoverUrl(candidate.coverUrl, candidate.origin),
      fileName,
    )
    ElMessage.success(`封面已保存为 ${fileName}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '封面下载失败')
  } finally {
    downloadingKey.value = ''
  }
}

watch(
  () => props.modelValue,
  open => {
    if (open) {
      startSearch()
    } else {
      previewIndex.value = null
      searchId++
      stopSearch?.()
      stopSearch = undefined
      searching.value = false
      canResumeSearch.value = false
    }
  },
)

onBeforeUnmount(() => stopSearch?.())
</script>

<style scoped lang="scss">
.cover-dialog-body {
  min-height: 300px;
}

.book-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.book-name {
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
}

.book-author,
.search-status {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.search-status {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.cover-grid,
.cover-skeletons {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 18px;
  margin-top: 18px;
}

.cover-result {
  min-width: 0;
}

.cover-preview,
.cover-error,
.skeleton-image {
  width: 100%;
  aspect-ratio: 3 / 4;
  border-radius: 4px;
}

.cover-preview {
  display: block;
  background: var(--el-fill-color-light);
  cursor: zoom-in;
}

.cover-error {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-size: 28px;
}

.cover-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  min-height: 38px;

  span {
    overflow: hidden;
    color: var(--el-text-color-regular);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.skeleton-text {
  width: 70%;
  margin-top: 10px;
}

@media screen and (max-width: 520px) {
  .book-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .cover-grid,
  .cover-skeletons {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
  }
}
</style>
