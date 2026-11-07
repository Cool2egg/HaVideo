import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://localhost:9090'
  const paths = ['/user', '/media', '/upload', '/pipeline', '/analysis', '/rag', '/knowledge', '/admin', '/health']
  return {
    envDir: '..',
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: Object.fromEntries(paths.map(path => [
        path,
        { target: backend, changeOrigin: true }
      ]))
    }
  }
})
