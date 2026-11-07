import { defineStore } from 'pinia'
import { api, getToken, setToken, clearToken } from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    user: null
  }),
  actions: {
    async login(username, password) {
      const data = await api('/user/login', {
        method: 'POST',
        body: { username, password }
      })
      setToken(data.token)
      this.token = data.token
      this.user = data
      return data
    },
    async register(username, password, nickname) {
      const data = await api('/user/register', {
        method: 'POST',
        body: { username, password, nickname }
      })
      setToken(data.token)
      this.token = data.token
      this.user = data
      return data
    },
    async fetchMe() {
      if (!this.token) return null
      this.user = await api('/user/me')
      return this.user
    },
    async logout() {
      try {
        await api('/user/logout', { method: 'POST' })
      } catch {
        // 会话已失效也允许退出
      }
      clearToken()
      this.token = ''
      this.user = null
    }
  }
})
