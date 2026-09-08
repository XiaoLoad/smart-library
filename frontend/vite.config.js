import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // 开发服务器：host 0.0.0.0 允许局域网设备通过本机 IP 访问
  server: {
    host: '0.0.0.0',
    port: 5173,
    open: false,
    proxy: {
      // 代理在 vite 服务端执行（局域网用户请求 http://本机IP:5173/api 时，
      // 由 vite 转发到本机后端），目标用 127.0.0.1 避免 localhost 解析到 IPv6 ::1
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  // 构建产物预览（npm run preview）：preview 的 host/proxy 是独立配置，
  // 不配置则局域网无法访问且 /api 请求不会转发到后端
  preview: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
