import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { pinia } from './stores/pinia'
import './styles/variables.css'
import './styles/animations.css'
import './styles/global.css'

const app = createApp(App)
app.use(pinia)
app.use(router)
app.mount('#app')
