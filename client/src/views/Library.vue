<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { uploadVideo } from '../upload'

const router = useRouter()
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const loading = ref(false)
const fileInput = ref(null)
const uploadState = reactive({
  active: false,
  phase: '',
  percent: 0,
  name: '',
  text: ''
})

let uploadController = null
let timer = null

const statusMeta = {
  UPLOADING: { text: '上传中', type: 'info' },
  QUEUED: { text: '排队中', type: 'warning' },
  PROCESSING: { text: '解析中', type: 'warning' },
  READY: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' }
}

async function fetchList() {
  loading.value = true
  try {
    const data = await api(`/media?page=${page.value}&size=${size.value}`)
    list.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchList()
  timer = setInterval(() => {
    if (list.value.some(item => ['QUEUED', 'PROCESSING', 'UPLOADING'].includes(item.status))) {
      fetchList()
    }
  }, 5000)
})

onUnmounted(() => {
  clearInterval(timer)
  uploadController?.abort()
})

function pickFile() {
  fileInput.value?.click()
}

async function onFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (file.size <= 0) {
    ElMessage.warning('文件为空')
    return
  }
  uploadController?.abort()
  uploadController = new AbortController()
  uploadState.active = true
  uploadState.name = file.name
  uploadState.phase = 'hash'
  uploadState.percent = 0
  uploadState.text = '计算文件指纹…'
  try {
    const result = await uploadVideo(file, {
      signal: uploadController.signal,
      onProgress: progress => {
        uploadState.phase = progress.phase
        uploadState.percent = progress.percent || 0
        if (progress.phase === 'hash') {
          uploadState.text = `计算文件指纹 ${progress.percent}%`
        } else if (progress.phase === 'upload') {
          uploadState.text = `上传分片 ${progress.done}/${progress.total}`
        } else if (progress.phase === 'merge') {
          uploadState.text = '服务端合并文件…'
        } else {
          uploadState.text = '完成'
        }
      }
    })
    ElMessage.success(result.dedup ? '检测到相同视频，已复用已有记录' : '上传完成，开始解析')
    router.push(`/videos/${result.mediaId}`)
  } catch (error) {
    if (error.name !== 'AbortError') ElMessage.error(error.message)
  } finally {
    uploadState.active = false
  }
}

function cancelUpload() {
  uploadController?.abort()
  uploadState.active = false
  ElMessage.info('已取消上传')
}

function openDetail(item) {
  router.push(`/videos/${item.id}`)
}

async function removeItem(item, event) {
  event.stopPropagation()
  try {
    await ElMessageBox.confirm(`删除「${item.filename}」？`, '确认删除', { type: 'warning' })
    await api(`/media/${item.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    fetchList()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || '删除失败')
  }
}

function onPageChange(value) {
  page.value = value
  fetchList()
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">视频库</h2>
        <p class="muted">上传视频后自动完成转写、关键帧识别与知识库索引</p>
      </div>
      <div class="actions">
        <input ref="fileInput" type="file" accept="video/*" hidden @change="onFileChange" />
        <el-button type="primary" :disabled="uploadState.active" @click="pickFile">上传视频</el-button>
      </div>
    </div>

    <el-card v-if="uploadState.active" shadow="never" class="upload-card">
      <div class="upload-line">
        <span class="upload-name">{{ uploadState.name }}</span>
        <el-button link type="danger" @click="cancelUpload">取消</el-button>
      </div>
      <el-progress :percentage="uploadState.percent" :status="uploadState.phase === 'done' ? 'success' : ''" />
      <p class="muted">{{ uploadState.text }}</p>
    </el-card>

    <div v-loading="loading" class="media-grid">
      <div v-for="item in list" :key="item.id" class="media-card" @click="openDetail(item)">
        <div class="media-cover">
          <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.filename" />
          <span v-else>VIDEO</span>
        </div>
        <div class="media-body">
          <div class="media-name">{{ item.filename }}</div>
          <div class="tag-row">
            <el-tag :type="statusMeta[item.status]?.type || 'info'" size="small">
              {{ statusMeta[item.status]?.text || item.status }}
            </el-tag>
            <span class="muted">{{ item.progress || 0 }}%</span>
          </div>
          <el-progress
            v-if="['QUEUED', 'PROCESSING'].includes(item.status)"
            :percentage="item.progress || 0"
            :show-text="false"
            class="card-progress"
          />
          <p v-if="item.errorStage" class="muted error-text">
            {{ item.errorStage }}：{{ item.errorMessage || '处理失败' }}
          </p>
          <div class="card-footer">
            <span class="muted">{{ item.createdAt?.replace('T', ' ').slice(0, 16) }}</span>
            <el-button link type="danger" size="small" @click="removeItem(item, $event)">删除</el-button>
          </div>
        </div>
      </div>
    </div>

    <el-empty v-if="!loading && list.length === 0" description="还没有视频，先上传一个吧" />

    <div v-if="total > size" class="pager">
      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.actions {
  display: flex;
  gap: 10px;
}
.upload-card {
  margin-bottom: 18px;
}
.upload-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.upload-name {
  font-weight: 600;
  font-size: 14px;
  word-break: break-all;
}
.card-progress {
  margin-top: 8px;
}
.error-text {
  color: #d14343;
  margin: 8px 0 0;
  font-size: 12px;
}
.card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
}
.pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
