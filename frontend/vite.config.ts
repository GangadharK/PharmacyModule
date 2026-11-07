import react from '@vitejs/plugin-react'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const rootDir = path.dirname(fileURLToPath(import.meta.url))

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(rootDir, './src'),
    },
  },
  server: {
    // 5163 rather than Vite's 5173 default, which is the first port a second
    // local dev server claims - so a stale Vite left running on 5173 silently
    // pushes this one to 5174 and the tester's bookmark hits the wrong app.
    // strictPort below makes a clash fail loudly instead.
    port: 5163,
    strictPort: true,
    proxy: {
      // Proxying /api and /actuator to the backend means the browser sees a
      // same-origin request, so there is no CORS pre-flight in development and
      // the HttpOnly refresh cookie is sent normally. The alternative - calling
      // :8081 directly - makes every environment difference a debugging session.
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
