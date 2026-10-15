import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],build:{outDir:'build'},server:{proxy:{'/api':'http://127.0.0.1:5080','/hubs':{target:'http://127.0.0.1:5080',ws:true}}}})
