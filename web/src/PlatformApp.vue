<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { IconApps, IconUserGroup, IconHistory, IconRefresh, IconPlus, IconDown } from '@arco-design/web-vue/es/icon'
import { api, post } from './api'

const emit = defineEmits(['enterprise'])

const me = ref(null)
const page = ref('accounts')
const busy = ref(false)
const error = ref('')
const loginForm = reactive({ email: '', password: '' })

const accounts = ref([])
const accountsTotal = ref(0)
const accountsLoading = ref(false)
const accountQuery = ref('')

const catalog = ref([])
const catalogLoading = ref(false)

const ledger = reactive({ items: [], total: 0 })
const ledgerLoading = ref(false)
const ledgerAccount = ref('')
const ledgerOffset = ref(0)
const ledgerLimit = 50

const modelModal = ref(false)
const modelEditing = ref(null)
const modelSaving = ref(false)
const modelForm = reactive({
  id: '', displayName: '', baseUrl: '', vendorModel: '', vendorKey: '',
  multiplier: 1.5, capabilitiesText: '{\n  "streaming": true,\n  "toolCalls": true\n}',
  contextTokens: 128000, maxOutputTokens: 32000, enabled: false, priceVersion: 1,
})

const keyModal = ref(false)
const keyTarget = ref(null)
const keyValue = ref('')
const keySaving = ref(false)

const creditForm = reactive({ email: '', amount: null, idempotencyKey: '', reason: '' })
const creditBusy = ref(false)
const creditResult = ref(null)
const accountsGap = ref('')
const ledgerGap = ref('')

const title = computed(() => ({ accounts: '账号与企业', catalog: '公开模型目录', ledger: '积分与流水' })[page.value])
const selectedAccount = computed(() => accounts.value.find(a => a.accountId === ledgerAccount.value) || null)
const accountOptions = computed(() => accounts.value.map(a => ({ value: a.accountId, label: `${a.email}${a.displayName ? ' · ' + a.displayName : ''}` })))
const accountById = computed(() => new Map(accounts.value.map(a => [a.accountId, a])))
const spaceKindById = computed(() => {
  const map = new Map()
  for (const a of accounts.value) for (const s of (a.spaces || [])) map.set(s.id, s.kind)
  return map
})

function emailFor(row) {
  return accountById.value.get(row.accountId)?.email || row.accountId
}

function spaceKindFor(row) {
  return spaceKindById.value.get(row.spaceId) || null
}

function fail(e) {
  error.value = e.message
  Message.error(e.message)
}

function fmtTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN') : '—'
}

function creditText(value) {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  return Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: 3 }) : String(value)
}

function deltaText(row) {
  const value = row.deltaCredits !== undefined && row.deltaCredits !== null
    ? Number(row.deltaCredits)
    : row.deltaUnits !== undefined && row.deltaUnits !== null ? Number(row.deltaUnits) / 1000 : null
  if (value === null || !Number.isFinite(value)) return '—'
  return (value > 0 ? '+' : '') + value.toLocaleString('zh-CN', { maximumFractionDigits: 3 })
}

function deltaClass(row) {
  const value = row.deltaUnits !== undefined && row.deltaUnits !== null
    ? Number(row.deltaUnits)
    : row.deltaCredits !== undefined && row.deltaCredits !== null ? Number(row.deltaCredits) * 1000 : 0
  return value > 0 ? 'amount-pos' : value < 0 ? 'amount-neg' : ''
}

function spaceKindLabel(kind) {
  return ({ PERSONAL: '个人空间', ENTERPRISE: '企业空间' })[kind] || kind || '—'
}

function roleLabel(role) {
  return ({ OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' })[role] || role || ''
}

function reasonLabel(reason) {
  return ({ TRIAL: '注册试用', ISSUE: '平台发放', USAGE: '托管调用', REFUND: '退回' })[reason] || reason || '—'
}

function statusLabel(status) {
  return ({ SETTLED: '已结算', RESERVED: '预占中', RELEASED: '已释放', REVIEW: '待核对' })[status] || status || '—'
}

function trialCredits(row) {
  if (!row.trial) return null
  if (row.trial.grantedCredits !== undefined && row.trial.grantedCredits !== null) return row.trial.grantedCredits
  if (row.trial.grantedUnits !== undefined && row.trial.grantedUnits !== null) return Number(row.trial.grantedUnits) / 1000
  return null
}

function enterpriseSpace(row) {
  return (row.spaces || []).find(s => s.kind === 'ENTERPRISE') || null
}

function capabilitiesText(capabilities) {
  if (capabilities === null || capabilities === undefined) return '—'
  try { return JSON.stringify(capabilities) } catch { return '—' }
}

function capsSummary(capabilities) {
  if (Array.isArray(capabilities)) return capabilities.join(', ') || '—'
  if (capabilities && typeof capabilities === 'object') {
    const parts = Object.keys(capabilities).map(key => capabilities[key] === true ? key : `${key}:${JSON.stringify(capabilities[key])}`)
    return parts.join(', ') || '—'
  }
  return '—'
}

async function login() {
  busy.value = true
  error.value = ''
  try {
    await post('/account/browser-login', loginForm)
    const session = await api('/account/me')
    if (session?.account?.platformRole !== 'SUPERADMIN') {
      await post('/account/logout', {}).catch(() => {})
      error.value = '该账号不是平台管理员'
      return
    }
    me.value = session
    loginForm.password = ''
    await loadAll()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await post('/account/logout', {})
    me.value = null
    accounts.value = []
    catalog.value = []
    ledger.items = []
  } catch (e) {
    Message.error(e.message)
  }
}

function navigate(key) {
  page.value = key
  error.value = ''
  if (key === 'accounts') loadAccounts()
  if (key === 'catalog') loadCatalog()
  if (key === 'ledger') { loadAccounts(); loadLedger() }
}

async function loadAll() {
  await Promise.all([loadAccounts(), loadCatalog(), loadLedger()])
}

async function loadAccounts() {
  accountsLoading.value = true
  accountsGap.value = ''
  try {
    const query = accountQuery.value.trim()
    const data = await api(`/admin/platform/accounts?limit=100&offset=0${query ? '&query=' + encodeURIComponent(query) : ''}`)
    accounts.value = data?.accounts || []
    accountsTotal.value = data?.total ?? accounts.value.length
  } catch (e) {
    if (e.status === 404) accountsGap.value = '账号接口尚未部署：' + e.message
    else fail(e)
  } finally {
    accountsLoading.value = false
  }
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    catalog.value = (await api('/admin/platform/models')) || []
  } catch (e) {
    fail(e)
  } finally {
    catalogLoading.value = false
  }
}

async function loadLedger() {
  ledgerLoading.value = true
  ledgerGap.value = ''
  try {
    const params = new URLSearchParams({ limit: String(ledgerLimit), offset: String(ledgerOffset.value) })
    if (ledgerAccount.value) params.set('accountId', ledgerAccount.value)
    const data = await api('/admin/platform/ledger?' + params.toString())
    ledger.items = data?.entries || []
    ledger.total = data?.total ?? ledger.items.length
  } catch (e) {
    if (e.status === 404) ledgerGap.value = '流水接口尚未部署：' + e.message
    else fail(e)
  } finally {
    ledgerLoading.value = false
  }
}

function openModel(model = null) {
  modelEditing.value = model
  Object.assign(modelForm, {
    id: model?.id || '',
    displayName: model?.displayName || '',
    baseUrl: model?.baseUrl || '',
    vendorModel: model?.vendorModel || '',
    vendorKey: '',
    multiplier: model ? model.multiplierBasisPoints / 10000 : 1.5,
    capabilitiesText: model ? JSON.stringify(model.capabilities ?? {}, null, 2) : '{\n  "streaming": true,\n  "toolCalls": true\n}',
    contextTokens: model?.contextTokens || 128000,
    maxOutputTokens: model?.maxOutputTokens || 32000,
    enabled: model?.enabled ?? false,
    priceVersion: model?.priceVersion ?? 1,
  })
  modelModal.value = true
}

function parseCapabilities(text) {
  let value
  try { value = JSON.parse(text) } catch { throw new Error('能力配置必须是合法的 JSON 对象或数组') }
  if (!value || typeof value !== 'object') throw new Error('能力配置必须是 JSON 对象或数组')
  return value
}

async function saveModel() {
  error.value = ''
  try {
    if (!modelForm.displayName.trim()) { Message.warning('请填写显示名称'); return false }
    if (!modelForm.baseUrl.trim()) { Message.warning('请填写上游 Base URL'); return false }
    if (!modelForm.vendorModel.trim()) { Message.warning('请填写供应商模型 ID'); return false }
    if (!modelEditing.value && !modelForm.id.trim()) { Message.warning('请填写模型 ID'); return false }
    if (!modelEditing.value && !modelForm.vendorKey.trim()) { Message.warning('请填写供应商 Key'); return false }
    const multiplierBasisPoints = Math.round(Number(modelForm.multiplier) * 10000)
    if (!Number.isFinite(multiplierBasisPoints) || multiplierBasisPoints < 1 || multiplierBasisPoints > 100000) {
      Message.warning('倍率必须在 0.0001 到 10 之间'); return false
    }
    const contextTokens = Number(modelForm.contextTokens)
    const maxOutputTokens = Number(modelForm.maxOutputTokens)
    if (!contextTokens || !maxOutputTokens) { Message.warning('请填写上下文和最大输出 token'); return false }
    if (maxOutputTokens > contextTokens) { Message.warning('最大输出 token 不能超过上下文 token'); return false }
    const capabilities = parseCapabilities(modelForm.capabilitiesText)
    const body = {
      displayName: modelForm.displayName.trim(),
      protocol: 'openai-chat-completions',
      baseUrl: modelForm.baseUrl.trim(),
      vendorModel: modelForm.vendorModel.trim(),
      multiplierBasisPoints,
      capabilities,
      contextTokens,
      maxOutputTokens,
      enabled: modelForm.enabled,
    }
    let saved
    modelSaving.value = true
    if (modelEditing.value) {
      body.expectedPriceVersion = modelEditing.value.priceVersion
      saved = await api(`/admin/platform/models/${encodeURIComponent(modelEditing.value.id)}`, { method: 'PUT', body: JSON.stringify(body) })
    } else {
      body.id = modelForm.id.trim()
      body.vendorKey = modelForm.vendorKey
      saved = await post('/admin/platform/models', body)
    }
    modelModal.value = false
    await loadCatalog()
    Message.success(`模型已保存，价格版本 v${saved.priceVersion}`)
    return true
  } catch (e) {
    fail(e)
    return false
  } finally {
    modelSaving.value = false
  }
}

async function toggleModel(model) {
  try {
    const saved = await post(`/admin/platform/models/${encodeURIComponent(model.id)}/enabled`, { enabled: !model.enabled })
    Object.assign(model, saved)
    Message.success(saved.enabled ? '模型已启用' : '模型已停用')
  } catch (e) {
    fail(e)
  }
}

function openKey(model) {
  keyTarget.value = model
  keyValue.value = ''
  keyModal.value = true
}

async function saveKey() {
  error.value = ''
  if (!keyValue.value.trim()) { Message.warning('请输入新的供应商 Key'); return false }
  keySaving.value = true
  try {
    await post(`/admin/platform/models/${encodeURIComponent(keyTarget.value.id)}/vendor-key`, { vendorKey: keyValue.value })
    keyModal.value = false
    keyValue.value = ''
    Message.success('供应商 Key 已轮换，旧 Key 立即失效')
    await loadCatalog()
    return true
  } catch (e) {
    fail(e)
    return false
  } finally {
    keySaving.value = false
  }
}

async function issueCredits() {
  error.value = ''
  creditResult.value = null
  if (!creditForm.email.trim()) return Message.warning('请填写接收积分的账号邮箱')
  const amount = Number(creditForm.amount)
  if (!Number.isFinite(amount) || amount <= 0) return Message.warning('请填写大于 0 的积分数量')
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(creditForm.idempotencyKey.trim())) {
    return Message.warning('幂等键需以字母或数字开头，仅含字母、数字、点、下划线、冒号或短横线，最长 128 位')
  }
  if (!creditForm.reason.trim()) return Message.warning('请填写发放原因')
  creditBusy.value = true
  try {
    const result = await post('/admin/platform/credits', {
      email: creditForm.email.trim(),
      amountUnits: Math.round(amount * 1000),
      idempotencyKey: creditForm.idempotencyKey.trim(),
      reason: creditForm.reason.trim(),
    })
    creditResult.value = result
    creditForm.idempotencyKey = ''
    creditForm.reason = ''
    Message.success('积分已发放')
    ledgerAccount.value = result.accountId
    ledgerOffset.value = 0
    await Promise.all([loadAccounts(), loadLedger()])
  } catch (e) {
    fail(e)
  } finally {
    creditBusy.value = false
  }
}

function goLedger(account) {
  ledgerAccount.value = account.accountId
  ledgerOffset.value = 0
  page.value = 'ledger'
  loadLedger()
}

onMounted(async () => {
  try {
    const session = await api('/account/me')
    if (session?.account?.platformRole === 'SUPERADMIN') {
      me.value = session
      await loadAll()
    }
  } catch {
    me.value = null
  }
})
</script>

<template>
  <div v-if="!me" class="login-wrap">
    <section class="login-card">
      <div class="brand"><img class="brand-mark" src="/sanyun-buddy.png" alt="" /><strong>三云Buddy</strong><span class="brand-divider"></span><span>平台控制台</span></div>
      <h1>登录平台控制台</h1>
      <p class="muted">管理平台账号、公开模型与积分结算</p>
      <form class="form-stack" @submit.prevent="login">
        <label>管理员邮箱<a-input v-model="loginForm.email" autocomplete="username" placeholder="admin@example.com" /></label>
        <label>密码<a-input-password v-model="loginForm.password" autocomplete="current-password" placeholder="输入密码" /></label>
        <a-alert v-if="error" type="error">{{ error }}</a-alert>
        <a-button type="primary" html-type="submit" long :loading="busy">登录</a-button>
      </form>
      <p class="login-switch">企业管理员？<a @click="emit('enterprise')">返回企业控制台</a></p>
    </section>
  </div>

  <div v-else class="console">
    <header class="header">
      <div class="brand"><img class="brand-mark" src="/sanyun-buddy.png" alt="" /><strong>三云Buddy</strong><span class="brand-divider"></span><span>平台控制台</span></div>
      <div class="header-right">
        <a-tag color="orange">平台管理员</a-tag>
        <a-dropdown @select="logout">
          <button class="account-menu"><a-avatar :size="28">{{ (me.account.displayName || me.account.email).slice(0, 1) }}</a-avatar>{{ me.account.displayName || me.account.email }}<icon-down /></button>
          <template #content><a-doption value="logout">退出登录</a-doption></template>
        </a-dropdown>
      </div>
    </header>

    <aside class="sidebar">
      <a-menu :selected-keys="[page]" @menu-item-click="navigate">
        <a-menu-item key="accounts"><template #icon><icon-user-group /></template>账号与企业</a-menu-item>
        <a-menu-item key="catalog"><template #icon><icon-apps /></template>公开模型目录</a-menu-item>
        <a-menu-item key="ledger"><template #icon><icon-history /></template>积分与流水</a-menu-item>
      </a-menu>
      <div class="sidebar-bottom"><a-avatar :size="32">{{ (me.account.displayName || me.account.email).slice(0, 1) }}</a-avatar><div>{{ me.account.displayName || me.account.email }}<small>平台超级管理员</small></div></div>
    </aside>

    <main class="workspace">
      <div class="page-heading">
        <div><div class="breadcrumb">平台控制台 / {{ title }}</div><h1>{{ title }}</h1></div>
        <a-button @click="page === 'accounts' ? loadAccounts() : page === 'catalog' ? loadCatalog() : Promise.all([loadAccounts(), loadLedger()])"><template #icon><icon-refresh /></template>刷新</a-button>
      </div>
      <a-alert v-if="error" type="error" style="margin-bottom:16px">{{ error }}</a-alert>

      <template v-if="page === 'accounts'">
        <section class="content-panel">
          <div class="platform-toolbar">
            <div>
              <h2>账号与企业</h2>
              <p class="muted" style="margin:6px 0 0">平台全部账号、个人空间、企业成员身份与试用积分。此页只读。</p>
            </div>
            <a-input-search v-model="accountQuery" placeholder="搜索邮箱或姓名" allow-clear style="width:260px" @search="loadAccounts" @clear="loadAccounts" />
          </div>
          <a-alert v-if="accountsGap" type="warning" style="margin-bottom:16px">{{ accountsGap }}</a-alert>
          <a-table :data="accounts" row-key="accountId" :loading="accountsLoading" :pagination="false" :bordered="false" :scroll="{ x: 920 }">
            <template #columns>
              <a-table-column title="账号" :width="220">
                <template #cell="{ record }">
                  <div class="member-cell"><a-avatar :size="32">{{ (record.displayName || record.email).slice(0, 1) }}</a-avatar><div><strong>{{ record.displayName || '未命名账号' }}</strong><small>{{ record.email }}</small></div></div>
                </template>
              </a-table-column>
              <a-table-column title="平台角色" :width="90">
                <template #cell="{ record }"><a-tag v-if="record.platformRole === 'SUPERADMIN'" color="orange" size="small">平台管理员</a-tag><span v-else class="muted">普通账号</span></template>
              </a-table-column>
              <a-table-column title="状态" :width="110">
                <template #cell="{ record }"><span class="status-dot" :class="{ disabled: !record.active }"></span>{{ record.active ? '已启用' : '已停用' }}<small class="muted" style="display:block;margin-top:4px">{{ record.emailVerified ? '邮箱已验证' : '邮箱未验证' }}</small></template>
              </a-table-column>
              <a-table-column title="空间" :width="220">
                <template #cell="{ record }">
                  <div v-if="record.spaces && record.spaces.length" class="space-tags">
                    <a-tag v-for="space in record.spaces" :key="space.id" size="small" :color="space.kind === 'PERSONAL' ? 'blue' : 'green'">{{ spaceKindLabel(space.kind) }}<span v-if="space.kind === 'ENTERPRISE'"> · {{ space.organizationName || space.name || '企业' }} · {{ roleLabel(space.role) }}</span></a-tag>
                  </div>
                  <span v-else class="muted">无空间</span>
                </template>
              </a-table-column>
              <a-table-column title="试用积分" :width="95">
                <template #cell="{ record }"><span v-if="trialCredits(record) !== null">{{ creditText(trialCredits(record)) }}</span><span v-else class="muted">未发放</span></template>
              </a-table-column>
              <a-table-column title="可用余额" :width="95">
                <template #cell="{ record }"><strong>{{ creditText(record.wallet?.availableCredits) }}</strong></template>
              </a-table-column>
              <a-table-column title="操作" :width="90" fixed="right">
                <template #cell="{ record }"><a-button type="text" size="small" @click="goLedger(record)">查看流水</a-button></template>
              </a-table-column>
            </template>
            <template #empty><a-empty description="暂无平台账号" /></template>
          </a-table>
          <p class="muted" style="margin-top:12px">共 {{ accountsTotal }} 个账号<span v-if="accounts.length < accountsTotal">，当前显示前 {{ accounts.length }} 个</span></p>
        </section>
      </template>

      <template v-else-if="page === 'catalog'">
        <section class="content-panel model-panel">
          <div class="provider-heading">
            <div><h2>公开模型目录</h2><p class="muted">发布后，桌面端「三云 Buddy」分组可见。供应商 Key 只写不读，任何接口都不会返回。</p></div>
            <a-button type="primary" @click="openModel()"><template #icon><icon-plus /></template>添加模型</a-button>
          </div>
          <a-table :data="catalog" row-key="id" :loading="catalogLoading" :pagination="false" :bordered="false" :scroll="{ x: 920 }" style="margin-top:24px">
            <template #columns>
              <a-table-column title="模型" :width="300">
                <template #cell="{ record }"><div><code>{{ record.id }}</code></div><strong>{{ record.displayName }}</strong><small class="muted" style="display:block">{{ record.vendorModel }} · {{ record.baseUrl }}</small></template>
              </a-table-column>
              <a-table-column title="倍率" :width="80"><template #cell="{ record }"><a-tag color="arcoblue">×{{ record.multiplier }}</a-tag></template></a-table-column>
              <a-table-column title="上下文 / 输出" :width="110"><template #cell="{ record }">{{ record.contextTokens }} / {{ record.maxOutputTokens }}</template></a-table-column>
              <a-table-column title="能力" :width="120"><template #cell="{ record }"><a-tooltip :content="capabilitiesText(record.capabilities)"><span class="capabilities-code">{{ capsSummary(record.capabilities) }}</span></a-tooltip></template></a-table-column>
              <a-table-column title="价格版本" :width="90"><template #cell="{ record }"><a-tag size="small">v{{ record.priceVersion }}</a-tag></template></a-table-column>
              <a-table-column title="状态" :width="70"><template #cell="{ record }"><a-switch :model-value="record.enabled" size="small" @change="toggleModel(record)" /></template></a-table-column>
              <a-table-column title="操作" :width="150" fixed="right">
                <template #cell="{ record }"><a-button type="text" size="small" @click="openModel(record)">编辑</a-button><a-button type="text" size="small" @click="openKey(record)">轮换 Key</a-button></template>
              </a-table-column>
            </template>
            <template #empty><a-empty description="模型目录为空。目录为空时用户仍可登录并使用自配置模型。" /></template>
          </a-table>
        </section>
      </template>

      <template v-else>
        <section class="content-panel">
          <h2>发放积分</h2>
          <p class="muted">按账号邮箱发放到个人空间。幂等键相同的重复提交不会重复加分。</p>
          <form class="credit-form" @submit.prevent="issueCredits">
            <label class="model-field"><span class="field-label">账号邮箱</span><a-input v-model="creditForm.email" placeholder="user@example.com" /></label>
            <label class="model-field"><span class="field-label">积分数量（1 积分 = 1000 单位）</span><a-input-number v-model="creditForm.amount" :min="0.001" :precision="3" :step="1" placeholder="例如 500" /></label>
            <label class="model-field"><span class="field-label">幂等键</span><a-input v-model="creditForm.idempotencyKey" placeholder="例如 grant-2026-11-22-001" /></label>
            <label class="model-field"><span class="field-label">发放原因</span><a-input v-model="creditForm.reason" :max-length="200" placeholder="例如 内测用户试用积分" /></label>
            <div class="credit-actions"><a-button type="primary" html-type="submit" :loading="creditBusy">发放积分</a-button></div>
          </form>
          <a-alert v-if="creditResult" type="success" class="credit-result">已发放 {{ creditText(Number(creditResult.amountUnits) / 1000) }} 积分，当前可用 {{ creditResult.availableCredits }} 积分（参考号 {{ creditResult.reference }}）</a-alert>

          <div class="ledger-heading">
            <div><h2>积分与流水</h2><p class="muted">流水只记录账号、空间、模型、用量、费率版本、积分和请求 ID，不含聊天正文。</p></div>
            <a-select v-model="ledgerAccount" :options="accountOptions" placeholder="全部账号" allow-clear style="width:280px" @change="ledgerOffset = 0; loadLedger()" />
          </div>
          <div v-if="selectedAccount" class="balance-strip">
            <div class="balance-card"><span>可用积分</span><strong>{{ creditText(selectedAccount.wallet?.availableCredits) }}</strong></div>
            <div class="balance-card"><span>账户余额</span><strong>{{ creditText(selectedAccount.wallet?.balanceCredits) }}</strong></div>
            <div class="balance-card"><span>预占积分</span><strong>{{ creditText(selectedAccount.wallet?.reservedUnits !== undefined && selectedAccount.wallet?.reservedUnits !== null ? Number(selectedAccount.wallet.reservedUnits) / 1000 : null) }}</strong></div>
          </div>
          <a-alert v-if="ledgerGap" type="warning" style="margin-bottom:16px">{{ ledgerGap }}</a-alert>
          <a-table :data="ledger.items" row-key="id" :loading="ledgerLoading" :pagination="false" :bordered="false" :scroll="{ x: 1420 }">
            <template #columns>
              <a-table-column title="时间" :width="170"><template #cell="{ record }">{{ fmtTime(record.createdAt) }}</template></a-table-column>
              <a-table-column title="账号" :width="220"><template #cell="{ record }"><div>{{ emailFor(record) }}</div><small v-if="emailFor(record) !== record.accountId" class="muted">{{ record.accountId }}</small></template></a-table-column>
              <a-table-column title="空间" :width="100"><template #cell="{ record }">{{ spaceKindLabel(spaceKindFor(record)) }}</template></a-table-column>
              <a-table-column title="原因" :width="100"><template #cell="{ record }">{{ reasonLabel(record.reason) }}</template></a-table-column>
              <a-table-column title="变动积分" :width="110"><template #cell="{ record }"><strong :class="deltaClass(record)">{{ deltaText(record) }}</strong></template></a-table-column>
              <a-table-column title="变动后余额" :width="120"><template #cell="{ record }">{{ creditText(record.balanceAfterCredits !== undefined && record.balanceAfterCredits !== null ? record.balanceAfterCredits : record.balanceAfterUnits !== undefined && record.balanceAfterUnits !== null ? Number(record.balanceAfterUnits) / 1000 : null) }}</template></a-table-column>
              <a-table-column title="模型" :width="130"><template #cell="{ record }"><code v-if="record.request?.modelId">{{ record.request.modelId }}</code><span v-else class="muted">—</span></template></a-table-column>
              <a-table-column title="用量（输入 / 输出）" :width="150"><template #cell="{ record }"><span v-if="record.request && (record.request.inputTokens !== undefined || record.request.outputTokens !== undefined)">{{ record.request.inputTokens ?? 0 }} / {{ record.request.outputTokens ?? 0 }}</span><span v-else class="muted">—</span></template></a-table-column>
              <a-table-column title="状态" :width="100"><template #cell="{ record }"><span v-if="record.request?.status">{{ statusLabel(record.request.status) }}</span><span v-else class="muted">—</span></template></a-table-column>
              <a-table-column title="请求 ID" :width="220"><template #cell="{ record }"><code v-if="record.request?.requestId">{{ record.request.requestId }}</code><span v-else class="muted">—</span></template></a-table-column>
              <a-table-column title="参考号" :width="180"><template #cell="{ record }"><code>{{ record.reference }}</code></template></a-table-column>
            </template>
            <template #empty><a-empty description="暂无积分流水" /></template>
          </a-table>
          <div class="ledger-pager">
            <span class="muted">共 {{ ledger.total }} 条</span>
            <a-space><a-button size="small" :disabled="ledgerOffset === 0" @click="ledgerOffset = Math.max(0, ledgerOffset - ledgerLimit); loadLedger()">上一页</a-button><a-button size="small" :disabled="ledgerOffset + ledgerLimit >= ledger.total" @click="ledgerOffset += ledgerLimit; loadLedger()">下一页</a-button></a-space>
          </div>
        </section>
      </template>
    </main>
  </div>

  <a-modal v-model:visible="modelModal" :title="modelEditing ? '编辑模型' : '添加模型'" :width="640" :footer="false" :unmount-on-close="true">
    <a-form :model="modelForm" layout="vertical">
      <a-form-item label="模型 ID" required>
        <a-input v-model="modelForm.id" :disabled="!!modelEditing" placeholder="例如 sanyun-buddy-chat" />
        <template #help>稳定 ID，桌面端用它选择模型；创建后不可修改。</template>
      </a-form-item>
      <a-form-item label="显示名称" required><a-input v-model="modelForm.displayName" :max-length="160" placeholder="例如 三云 Buddy 对话模型" /></a-form-item>
      <a-form-item label="协议"><a-input model-value="OpenAI Chat Completions" disabled /><template #help>首版只支持 OpenAI Chat Completions 兼容协议。</template></a-form-item>
      <a-form-item label="上游 Base URL（HTTPS）" required><a-input v-model="modelForm.baseUrl" placeholder="https://api.example.com/v1" /></a-form-item>
      <a-form-item label="供应商模型 ID" required><a-input v-model="modelForm.vendorModel" :max-length="160" placeholder="例如 deepseek-chat" /></a-form-item>
      <a-form-item label="倍率（×）" required><a-input-number v-model="modelForm.multiplier" :min="0.0001" :max="10" :precision="4" :step="0.1" /><template #help>1.5 表示扣费 ×1.5。修改倍率会把价格版本 +1，旧价格请求会被拒绝。</template></a-form-item>
      <a-form-item label="能力配置（JSON 对象或数组）" required><a-textarea v-model="modelForm.capabilitiesText" :auto-size="{ minRows: 4, maxRows: 10 }" placeholder='{"streaming": true, "toolCalls": true}' /></a-form-item>
      <a-form-item label="上下文 token" required><a-input-number v-model="modelForm.contextTokens" :min="1" :max="10000000" /></a-form-item>
      <a-form-item label="最大输出 token" required><a-input-number v-model="modelForm.maxOutputTokens" :min="1" :max="10000000" /></a-form-item>
      <a-form-item v-if="!modelEditing" label="供应商 Key" required><a-input-password v-model="modelForm.vendorKey" autocomplete="new-password" placeholder="只写入，保存后不会显示" /></a-form-item>
      <a-form-item label="发布状态"><a-switch v-model="modelForm.enabled" /><span class="muted" style="margin-left:12px">关闭时桌面端不可见</span></a-form-item>
      <a-form-item v-if="modelEditing" label="当前价格版本"><a-tag>v{{ modelEditing.priceVersion }}</a-tag></a-form-item>
    </a-form>
    <div class="modal-actions"><a-button @click="modelModal = false">取消</a-button><a-button type="primary" :loading="modelSaving" @click="saveModel">保存</a-button></div>
  </a-modal>

  <a-modal v-model:visible="keyModal" title="轮换供应商 Key" :width="520" :footer="false" :unmount-on-close="true">
    <p class="muted">模型 {{ keyTarget?.id }} 的供应商 Key 只写入服务器加密存储，保存后不会再次显示。</p>
    <a-input-password v-model="keyValue" autocomplete="new-password" placeholder="输入新的供应商 Key" />
    <div class="modal-actions"><a-button @click="keyModal = false">取消</a-button><a-button type="primary" :loading="keySaving" @click="saveKey">轮换</a-button></div>
  </a-modal>
</template>
