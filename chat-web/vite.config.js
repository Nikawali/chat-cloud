import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  // Electron 打包后用 file:// 加载，必须用相对路径，否则静态资源 404
  base: './',
  server: {
    port: 5173,
    // 开发时把 /api 代理到后端网关，避免跨域
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
