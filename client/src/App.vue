<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const router = useRouter()

onMounted(() => {
  if (auth.token && !auth.user) auth.fetchMe().catch(() => {})
})

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand" @click="router.push('/')">VideoRAG</div>
      <nav v-if="auth.token" class="nav">
        <router-link to="/">视频库</router-link>
        <router-link to="/qa">知识问答</router-link>
        <router-link v-if="auth.user?.role === 'ADMIN'" to="/manage">管理</router-link>
      </nav>
      <div class="spacer" />
      <div v-if="auth.token" class="user">
        <span class="nickname">{{ auth.user?.nickname || auth.user?.username || '用户' }}</span>
        <el-button link type="primary" @click="logout">退出</el-button>
      </div>
      <router-link v-else to="/login" class="login-link">登录</router-link>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>
