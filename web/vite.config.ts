import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import legacy from '@vitejs/plugin-legacy'
export default defineConfig({
  plugins:[vue(),legacy({targets:['Chrome >= 60'],modernTargets:['Chrome >= 60'],modernPolyfills:true})],
  build:{outDir:'build'},
  server:{proxy:{'/api':'http://127.0.0.1:5080','/hubs':{target:'http://127.0.0.1:5080',ws:true}}}
})
