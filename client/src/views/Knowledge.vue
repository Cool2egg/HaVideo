<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, streamSSE } from '../api'

const router = useRouter()
const sessions = ref([])
const mediaList = ref([])
const activeSessionId = ref(null)
const messages = ref([])
const currentSources = ref([])
const question = ref('')
const sending = ref(false)
const streamingAnswer = ref('')
const messageBox = ref(null)

const dialogVisible = ref(false)
const sessionTitle = ref('')
const scopeMediaId = ref('')

let streamController = null

onMounted(async () => {
  await Promise.all([loadSessions(), loadMedia()])
})

onUnmounted(() => {
  streamController?.abort()
})

async function loadSessions() {
  try {
    sessions.value = await api('/rag/sessions')
    if (!activeSessionId.value && sessions.value.length) {
      await selectSession(sessions.value[0].id)
    }
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function loadMedia() {
  try {
    const data = await api('/media?page=1&size=100')
    mediaList.value = data.items || []
  } catch {
    mediaList.value = []
  }
}

async function selectSession(id) {
  activeSessionId.value = id
  streamingAnswer.value = ''
  try {
    messages.value = await api(`/rag/sessions/${id}/messages`)
    const lastAssistant = [...messages.value].reverse().find(item => item.role === 'assistant')
    currentSources.value = parseSources(lastAssistant?.sourcesJson)
    scrollToBottom()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function parseSources(value) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function openCreateDialog() {
  sessionTitle.value = ''
  scopeMediaId.value = ''
  dialogVisible.value = true
}

async function createSession() {
  try {
    const session = await api('/rag/sessions', {
      method: 'POST',
      body: {
        mediaId: scopeMediaId.value ? Number(scopeMediaId.value) : null,
        title: sessionTitle.value || null
      }
    })
    dialogVisible.value = false
    sessions.value.unshift(session)
    activeSessionId.value = session.id
    messages.value = []
    currentSources.value = []
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function deleteSession(session, event) {
  event.stopPropagation()
  try {
    await ElMessageBox.confirm(`删除会话「${session.title}」？`, '确认删除', { type: 'warning' })
    await api(`/rag/sessions/${session.id}`, { method: 'DELETE' })
    sessions.value = sessions.value.filter(item => item.id !== session.id)
    if (activeSessionId.value === session.id) {
      activeSessionId.value = null
      messages.value = []
      currentSources.value = []
      if (sessions.value.length) selectSession(sessions.value[0].id)
    }
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(error.message || '删除失败')
  }
}

async function send() {
  const text = question.value.trim()
  if (!text || sending.value) return
  if (!activeSessionId.value) {
    ElMessage.warning('请先新建或选择一个会话')
    return
  }

  messages.value.push({ role: 'user', content: text })
  question.value = ''
  streamingAnswer.value = ''
  currentSources.value = []
  sending.value = true
  scrollToBottom()

  streamController = streamSSE('/rag/chat', {
    sessionId: activeSessionId.value,
    question: text
  }, {
    start: () => {
      streamingAnswer.value = ''
    },
    sources: payload => {
      currentSources.value = Array.isArray(payload) ? payload : []
    },
    delta: payload => {
      streamingAnswer.value += payload.content || ''
      scrollToBottom()
    },
    done: () => {
      finishStream(text)
    },
    error: payload => {
      ElMessage.error(payload?.message || '回答生成失败')
      finishStream(text)
    }
  })
}

function finishStream() {
  if (streamingAnswer.value.trim()) {
    messages.value.push({
      role: 'assistant',
      content: streamingAnswer.value,
      sourcesJson: JSON.stringify(currentSources.value)
    })
  }
  streamingAnswer.value = ''
  sending.value = false
  streamController = null
  loadSessions()
  scrollToBottom()
}

function stop() {
  streamController?.abort()
  streamController = null
  if (streamingAnswer.value.trim()) {
    messages.value.push({
      role: 'assistant',
      content: streamingAnswer.value,
      sourcesJson: JSON.stringify(currentSources.value)
    })
  }
  streamingAnswer.value = ''
  sending.value = false
}

function showMessageSources(message) {
  if (message.role !== 'assistant') return
  currentSources.value = parseSources(message.sourcesJson)
}

function openSource(source) {
  router.push(`/videos/${source.mediaId}?t=${Math.floor((source.startMs || 0) / 1000)}`)
}

function formatTime(millis) {
  const seconds = Math.max(0, Math.floor((millis || 0) / 1000))
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

function scrollToBottom() {
  nextTick(() => {
    if (messageBox.value) messageBox.value.scrollTop = messageBox.value.scrollHeight
  })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">知识问答</h2>
        <p class="muted">基于视频解析结果作答，引用可跳转到原视频时间点</p>
      </div>
      <el-button type="primary" @click="openCreateDialog">新建对话</el-button>
    </div>

    <div class="chat-layout">
      <div class="panel session-panel">
        <h3 class="section-title">会话</h3>
        <div
          v-for="session in sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: session.id === activeSessionId }"
          @click="selectSession(session.id)"
        >
          <div class="session-main">
            <div class="session-title">{{ session.title }}</div>
            <div class="muted">{{ session.mediaId ? `视频 #${session.mediaId}` : '全部视频' }}</div>
          </div>
          <el-button link type="danger" size="small" @click="deleteSession(session, $event)">删</el-button>
        </div>
        <el-empty v-if="sessions.length === 0" description="暂无会话" :image-size="60" />
      </div>

      <div class="panel chat-panel">
        <div ref="messageBox" class="chat-messages">
          <template v-if="activeSessionId">
            <div
              v-for="(message, index) in messages"
              :key="index"
              class="chat-bubble"
              :class="message.role"
              @click="showMessageSources(message)"
            >
              {{ message.content }}
            </div>
            <div v-if="streamingAnswer" class="chat-bubble assistant">{{ streamingAnswer }}</div>
            <div v-if="sending" class="muted">正在检索并生成回答…</div>
          </template>
          <el-empty v-else description="新建一个会话开始提问" />
        </div>

        <div class="chat-input">
          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="例如：这两个视频对同一问题分别是怎么说的？"
            :disabled="!activeSessionId"
            @keydown.enter.exact.prevent="send"
          />
          <div class="input-actions">
            <el-button v-if="sending" type="danger" plain @click="stop">停止</el-button>
            <el-button type="primary" :loading="sending" :disabled="!activeSessionId" @click="send">
              发送
            </el-button>
          </div>
        </div>
      </div>

      <div class="panel sources-panel">
        <h3 class="section-title">引用来源</h3>
        <div
          v-for="source in currentSources"
          :key="source.ref"
          class="source-item"
          @click="openSource(source)"
        >
          <div>
            <span class="source-ref">[{{ source.ref }}]</span>
            <strong>{{ source.title }}</strong>
          </div>
          <div class="muted">
            {{ formatTime(source.startMs) }} - {{ formatTime(source.endMs) }} · {{ source.source }}
          </div>
          <p>{{ source.snippet }}</p>
        </div>
        <el-empty v-if="currentSources.length === 0" description="本轮暂无引用" :image-size="60" />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" title="新建对话" width="420px">
      <el-form label-position="top">
        <el-form-item label="会话标题（可选）">
          <el-input v-model="sessionTitle" maxlength="120" placeholder="留空则使用默认标题" />
        </el-form-item>
        <el-form-item label="问答范围">
          <el-select v-model="scopeMediaId" placeholder="全部视频" style="width: 100%">
            <el-option label="全部视频（跨视频问答）" value="" />
            <el-option
              v-for="media in mediaList"
              :key="media.id"
              :label="media.filename"
              :value="media.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createSession">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.section-title {
  margin: 0 0 12px;
  font-size: 15px;
}
.session-panel {
  min-height: 560px;
}
.session-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 8px;
  cursor: pointer;
}
.session-item:hover {
  background: #f4f7ff;
}
.session-item.active {
  background: var(--brand-soft);
}
.session-main {
  min-width: 0;
}
.session-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 640px;
}
.chat-messages {
  flex: 1;
  overflow-y: auto;
}
.chat-input {
  border-top: 1px solid var(--line);
  padding-top: 12px;
}
.input-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.sources-panel {
  min-height: 560px;
  max-height: 640px;
  overflow-y: auto;
}
.source-item p {
  margin: 6px 0 0;
  color: var(--muted);
}
</style>
