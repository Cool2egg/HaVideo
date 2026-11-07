<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const activeTab = ref('tasks')
const tasks = ref([])
const failed = ref([])
const usage = ref([])
const loading = ref(false)
const statusFilter = ref('')

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadTasks(), loadFailed(), loadUsage()])
}

async function loadTasks() {
  loading.value = true
  try {
    const query = statusFilter.value ? `?status=${statusFilter.value}` : ''
    tasks.value = await api(`/admin/tasks${query}`)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

async function loadFailed() {
  try {
    failed.value = await api('/admin/failed')
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function loadUsage() {
  try {
    usage.value = await api('/admin/usage')
  } catch {
    usage.value = []
  }
}

async function requeue(task) {
  try {
    await api(`/admin/tasks/${task.id}/requeue`, { method: 'POST' })
    ElMessage.success('已重新投递')
    await loadAll()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function refresh() {
  loadAll()
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">管理后台</h2>
        <p class="muted">任务状态、失败重放与用量</p>
      </div>
      <div class="actions">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 150px"
                   @change="loadTasks">
          <el-option label="排队" value="PENDING" />
          <el-option label="运行中" value="RUNNING" />
          <el-option label="成功" value="SUCCESS" />
          <el-option label="重试中" value="RETRYING" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <div class="panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="任务" name="tasks">
          <el-table v-loading="loading" :data="tasks" size="small">
            <el-table-column prop="mediaId" label="视频 ID" width="90" />
            <el-table-column prop="filename" label="文件名" min-width="180" show-overflow-tooltip />
            <el-table-column prop="stage" label="阶段" width="110" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="attempt" label="尝试" width="70" />
            <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click="requeue(row)">重放</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && tasks.length === 0" description="暂无任务" />
        </el-tab-pane>

        <el-tab-pane label="失败任务" name="failed">
          <el-table :data="failed" size="small">
            <el-table-column prop="mediaId" label="视频 ID" width="90" />
            <el-table-column prop="filename" label="文件名" min-width="180" show-overflow-tooltip />
            <el-table-column prop="stage" label="阶段" width="110" />
            <el-table-column prop="attempt" label="尝试" width="70" />
            <el-table-column prop="errorType" label="错误类型" width="150" />
            <el-table-column prop="errorMessage" label="错误信息" min-width="240" show-overflow-tooltip />
          </el-table>
          <el-empty v-if="failed.length === 0" description="暂无失败任务" />
        </el-tab-pane>

        <el-tab-pane label="Token 用量" name="usage">
          <el-table :data="usage" size="small">
            <el-table-column prop="username" label="用户" width="120" />
            <el-table-column prop="userId" label="用户 ID" width="90" />
            <el-table-column prop="statDate" label="日期" width="120" />
            <el-table-column prop="calls" label="调用次数" width="110" />
            <el-table-column prop="tokens" label="Token" width="120" />
            <el-table-column prop="audioSeconds" label="音频秒数" width="120" />
          </el-table>
          <el-empty v-if="usage.length === 0" description="暂无用量记录" />
        </el-tab-pane>
      </el-tabs>
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
</style>
