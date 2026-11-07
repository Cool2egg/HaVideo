<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, streamSSEGet } from '../api'

const route = useRoute()
const router = useRouter()

const media = ref(null)
const playbackUrl = ref('')
const videoRef = ref(null)
const loading = ref(false)
const stageMessage = ref('')
const activeTab = ref('summary')

const segments = ref([])
const keyframes = ref([])
const segmentsLoading = ref(false)
const keyframesLoading = ref(false)

const analysisGoal = ref('理解视频核心内容，提炼关键结论，并给出带时间戳的证据和可执行建议')
const analysisMode = ref('AUTO')
const effectiveMode = ref('')
const analysisStatus = ref(null)
const analysisResult = ref(null)
const analysisLoading = ref(false)

let eventController = null
let pollTimer = null
let analysisTimer = null
let currentId = null

const statusMeta = {
  UPLOADING: { text: '上传中', type: 'info' },
  QUEUED: { text: '排队中', type: 'warning' },
  PROCESSING: { text: '解析中', type: 'warning' },
  READY: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' }
}

const statusLabels = {
  PROBE: '读取视频信息',
  AUDIO: '提取音频',
  ASR: '语音转写',
  KEYFRAME: '抽取关键帧',
  OCR: '画面文字识别',
  SEGMENT: '整理时间轴',
  CHUNK: '生成知识分块',
  INDEX: '写入知识库',
  SUMMARY: '生成总结'
}

const summary = computed(() => {
  if (!media.value?.summaryJson) return null
  try {
    return JSON.parse(media.value.summaryJson)
  } catch {
    return null
  }
})

const analysisSections = computed(() => analysisResult.value?.sections || [])

onMounted(async () => {
  currentId = Number(route.params.id)
  await fetchMedia()
  connectEvents(currentId)
  pollTimer = setInterval(() => {
    if (media.value && ['QUEUED', 'PROCESSING'].includes(media.value.status)) {
      fetchMedia()
    }
  }, 4000)
})

onUnmounted(() => {
  eventController?.abort()
  clearInterval(pollTimer)
  clearInterval(analysisTimer)
})

watch(() => route.params.id, async value => {
  const id = Number(value)
  if (!id || id === currentId) return
  currentId = id
  eventController?.abort()
  clearInterval(analysisTimer)
  analysisResult.value = null
  analysisStatus.value = null
  segments.value = []
  keyframes.value = []
  await fetchMedia()
  connectEvents(id)
})

async function fetchMedia() {
  loading.value = true
  try {
    media.value = await api(`/media/${currentId}`)
    if (media.value.status === 'READY') {
      await loadPlayback()
      loadSegments()
      loadKeyframes()
    }
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

async function loadPlayback() {
  try {
    playbackUrl.value = await api(`/media/${currentId}/playback`)
  } catch (error) {
    playbackUrl.value = ''
  }
}

function connectEvents(id) {
  eventController = streamSSEGet(`/pipeline/${id}/events`, {
    progress: payload => {
      if (!payload || payload.mediaId !== id) return
      if (media.value) {
        media.value.status = payload.status
        media.value.progress = payload.progress
        media.value.errorMessage = payload.status === 'FAILED' ? payload.message : null
      }
      stageMessage.value = payload.message || ''
      if (payload.status === 'READY') {
        fetchMedia()
      }
    }
  })
}

async function loadSegments() {
  segmentsLoading.value = true
  try {
    const data = await api(`/media/${currentId}/segments`)
    segments.value = data.map(item => ({
      ...item,
      ocrList: parseJsonArray(item.ocrTexts)
    }))
  } catch (error) {
    segments.value = []
  } finally {
    segmentsLoading.value = false
  }
}

async function loadKeyframes() {
  keyframesLoading.value = true
  try {
    keyframes.value = await api(`/media/${currentId}/keyframes`)
  } catch (error) {
    keyframes.value = []
  } finally {
    keyframesLoading.value = false
  }
}

function parseJsonArray(value) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function handleVideoLoaded() {
  const seconds = Number(route.query.t)
  if (Number.isFinite(seconds) && seconds > 0) {
    seek(seconds * 1000)
  }
}

function seek(millis) {
  if (!videoRef.value) {
    ElMessage.info('视频尚未加载')
    return
  }
  videoRef.value.currentTime = Math.max(0, millis / 1000)
  videoRef.value.play().catch(() => {})
}

function formatTime(millis) {
  const seconds = Math.max(0, Math.floor((millis || 0) / 1000))
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

async function runAnalysis() {
  if (!media.value || media.value.status !== 'READY') {
    ElMessage.warning('视频尚未解析完成')
    return
  }
  if (!analysisGoal.value.trim()) {
    ElMessage.warning('请输入分析目标')
    return
  }
  analysisLoading.value = true
  analysisResult.value = null
  analysisStatus.value = null
  clearInterval(analysisTimer)
  try {
    let mode = analysisMode.value
    if (mode === 'AUTO') {
      const routed = await api('/analysis/route', {
        method: 'POST',
        body: { goal: analysisGoal.value.trim() }
      })
      mode = routed.mode
    }
    effectiveMode.value = mode
    const params = new URLSearchParams({ goal: analysisGoal.value.trim(), mode })
    const job = await api(`/analysis/${currentId}/run?${params}`, { method: 'POST' })
    applyJob(job, mode)
    if (job.status !== 'DONE' && job.status !== 'FAILED') {
      analysisTimer = setInterval(() => pollAnalysis(mode), 2000)
    }
  } catch (error) {
    ElMessage.error(error.message)
    analysisLoading.value = false
  }
}

async function pollAnalysis(mode) {
  try {
    const params = new URLSearchParams({ goal: analysisGoal.value.trim(), mode })
    const job = await api(`/analysis/${currentId}/status?${params}`)
    applyJob(job, mode)
    if (job.status === 'DONE' || job.status === 'FAILED') {
      clearInterval(analysisTimer)
      analysisLoading.value = false
    }
  } catch (error) {
    clearInterval(analysisTimer)
    analysisLoading.value = false
    ElMessage.error(error.message)
  }
}

function applyJob(job, mode) {
  analysisStatus.value = job
  if (job.status === 'DONE' && job.resultJson) {
    try {
      analysisResult.value = JSON.parse(job.resultJson)
    } catch {
      analysisResult.value = null
    }
    analysisLoading.value = false
  } else if (job.status === 'FAILED') {
    analysisLoading.value = false
    ElMessage.error(job.error || '分析失败')
  }
}

function goBack() {
  router.push('/')
}
</script>

<template>
  <div v-loading="loading">
    <div class="detail-head">
      <div>
        <el-button link @click="goBack">← 返回视频库</el-button>
        <h2 class="page-title">{{ media?.filename || '视频详情' }}</h2>
      </div>
      <div class="head-tags">
        <el-tag :type="statusMeta[media?.status]?.type || 'info'">
          {{ statusMeta[media?.status]?.text || media?.status }}
        </el-tag>
        <span class="muted">{{ media?.progress || 0 }}%</span>
      </div>
    </div>

    <el-alert
      v-if="media && ['QUEUED', 'PROCESSING'].includes(media.status)"
      :title="stageMessage || '正在解析视频'"
      type="warning"
      :closable="false"
      show-icon
      class="stage-alert"
    />
    <el-alert
      v-if="media?.status === 'FAILED'"
      :title="`${media.errorStage || '处理'}失败：${media.errorMessage || '请稍后重试'}`"
      type="error"
      :closable="false"
      show-icon
      class="stage-alert"
    />

    <div class="detail-grid">
      <div class="panel video-panel">
        <video
          v-if="playbackUrl"
          ref="videoRef"
          class="detail-video"
          :src="playbackUrl"
          controls
          @loadedmetadata="handleVideoLoaded"
        />
        <div v-else class="video-placeholder muted">视频处理完成后可播放</div>
        <el-progress
          v-if="media && ['QUEUED', 'PROCESSING'].includes(media.status)"
          :percentage="media.progress || 0"
          class="stage-progress"
        />
        <p class="muted">
          当前阶段：{{ media ? (media.status === 'READY' ? '处理完成' :
            (media.status === 'FAILED' ? '处理失败' : (stageMessage || '待处理'))) : '加载中' }}
        </p>
      </div>

      <div class="panel analysis-panel">
        <h3 class="section-title">AI 分析</h3>
        <el-input
          v-model="analysisGoal"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="例如：提炼视频的核心结论，并给出可执行建议"
        />
        <div class="analysis-actions">
          <el-select v-model="analysisMode" style="width: 160px">
            <el-option label="自动判断" value="AUTO" />
            <el-option label="通用理解" value="GENERAL" />
            <el-option label="学习" value="LEARNING" />
            <el-option label="审查" value="REVIEW" />
            <el-option label="创作" value="CREATION" />
          </el-select>
          <el-button
            type="primary"
            :loading="analysisLoading"
            :disabled="media?.status !== 'READY'"
            @click="runAnalysis"
          >
            开始分析
          </el-button>
        </div>
        <el-progress
          v-if="analysisLoading && analysisStatus"
          :percentage="analysisStatus.progress || 0"
          class="stage-progress"
        />
        <p v-if="analysisStatus && analysisLoading" class="muted">
          {{ analysisStatus.message }}
        </p>
        <div v-if="analysisResult" class="analysis-result">
          <h4>{{ analysisResult.title }}</h4>
          <p>{{ analysisResult.summary }}</p>
          <div v-for="(section, index) in analysisSections" :key="index" class="analysis-section">
            <div class="section-head">
              <strong>{{ section.title }}</strong>
              <button
                v-if="section.startMs !== undefined"
                class="timestamp-link"
                @click="seek(section.startMs)"
              >
                {{ formatTime(section.startMs) }}
              </button>
            </div>
            <p>{{ section.content }}</p>
            <div v-if="section.refs?.length" class="refs">
              <el-tag v-for="ref in section.refs" :key="ref" size="small">{{ ref }}</el-tag>
            </div>
          </div>
          <div v-if="analysisResult.keyPoints?.length">
            <h4>要点</h4>
            <ul>
              <li v-for="point in analysisResult.keyPoints" :key="point">{{ point }}</li>
            </ul>
          </div>
          <div v-if="analysisResult.extras && Object.keys(analysisResult.extras).length">
            <h4>扩展结果</h4>
            <pre class="extras">{{ JSON.stringify(analysisResult.extras, null, 2) }}</pre>
          </div>
        </div>
      </div>
    </div>

    <div class="panel tabs-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="结构化总结" name="summary">
          <template v-if="summary">
            <h3>{{ summary.oneLine }}</h3>
            <div v-if="summary.outline?.length" class="outline">
              <div v-for="(item, index) in summary.outline" :key="index" class="outline-item">
                <button class="timestamp-link" @click="seek(item.startMs)">
                  {{ formatTime(item.startMs) }}
                </button>
                <span>{{ item.title }}</span>
              </div>
            </div>
            <div v-if="summary.keyPoints?.length">
              <h4>关键要点</h4>
              <ul>
                <li v-for="point in summary.keyPoints" :key="point">{{ point }}</li>
              </ul>
            </div>
          </template>
          <el-empty v-else description="暂无结构化总结" />
        </el-tab-pane>

        <el-tab-pane label="时间轴字幕" name="transcript">
          <div v-loading="segmentsLoading">
            <div v-for="(segment, index) in segments" :key="index" class="transcript-row">
              <button class="timestamp-link transcript-time" @click="seek(segment.startMs)">
                {{ formatTime(segment.startMs) }}
              </button>
              <div>
                <p v-if="segment.transcript" class="transcript-text">{{ segment.transcript }}</p>
                <p v-for="text in segment.ocrList" :key="text" class="ocr-text">画面：{{ text }}</p>
              </div>
            </div>
            <el-empty v-if="!segmentsLoading && segments.length === 0" description="暂无字幕" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="关键帧" name="keyframes">
          <div v-loading="keyframesLoading" class="keyframe-grid">
            <div
              v-for="frame in keyframes"
              :key="frame.url"
              class="keyframe-item"
              @click="seek(frame.tsMs)"
            >
              <img :src="frame.url" alt="keyframe" loading="lazy" />
              <span>{{ formatTime(frame.tsMs) }}</span>
            </div>
          </div>
          <el-empty v-if="!keyframesLoading && keyframes.length === 0" description="暂无关键帧" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.head-tags {
  display: flex;
  align-items: center;
  gap: 10px;
}
.stage-alert {
  margin-bottom: 16px;
}
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(360px, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.video-panel {
  padding: 14px;
}
.video-placeholder {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #0f172a;
  border-radius: 10px;
  color: #94a3b8;
}
.stage-progress {
  margin-top: 12px;
}
.section-title {
  margin: 0 0 12px;
  font-size: 16px;
}
.analysis-actions {
  display: flex;
  gap: 10px;
  margin: 12px 0;
}
.analysis-result h4 {
  margin: 14px 0 6px;
}
.analysis-section {
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
}
.section-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}
.refs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
}
.extras {
  background: #f6f8fc;
  border-radius: 8px;
  padding: 12px;
  font-size: 12px;
  overflow-x: auto;
}
.tabs-panel {
  margin-top: 8px;
}
.outline {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 12px 0;
}
.outline-item {
  display: flex;
  gap: 12px;
  align-items: center;
}
.transcript-text {
  margin: 0;
}
.ocr-text {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 13px;
}
</style>
