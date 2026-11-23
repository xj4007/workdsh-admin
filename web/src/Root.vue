<script setup>
import { onMounted, ref } from 'vue'
import App from './App.vue'
import PlatformApp from './PlatformApp.vue'
import { api } from './api'

const mode = ref('loading')

onMounted(async () => {
  if (window.location.hash === '#platform') {
    mode.value = 'platform'
    return
  }
  try {
    const session = await api('/account/me')
    mode.value = session?.account?.platformRole === 'SUPERADMIN' ? 'platform' : 'enterprise'
  } catch {
    mode.value = 'enterprise'
  }
})

function showPlatform() {
  window.location.hash = 'platform'
  mode.value = 'platform'
}

function showEnterprise() {
  history.replaceState(null, '', window.location.pathname + window.location.search)
  mode.value = 'enterprise'
}
</script>

<template>
  <div v-if="mode === 'loading'" class="login-wrap"><a-spin dot /></div>
  <PlatformApp v-else-if="mode === 'platform'" @enterprise="showEnterprise" />
  <App v-else @platform-login="showPlatform" />
</template>
