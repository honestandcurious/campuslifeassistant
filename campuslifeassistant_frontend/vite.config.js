import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    open: true
  }
})
// export default {
//     server: {
//         proxy: {
//             '/chat': {
//                 target: 'http://localhost:8088',
//                 changeOrigin: true
//             }
//         }
//     }
// }
