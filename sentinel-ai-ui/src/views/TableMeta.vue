<template>
  <div class="table-meta">
    <div class="page-header">
      <h2>表结构管理</h2>
      <div class="header-actions">
        <el-button @click="testConnection" :loading="testing" :disabled="refreshingDdl || refreshingIndex">
          测试平台连接
        </el-button>
        <el-button type="primary" @click="confirmRefreshDdl" :loading="refreshingDdl" :disabled="refreshingIndex">
          刷新 DDL
        </el-button>
        <el-button type="warning" @click="confirmRefreshIndex" :loading="refreshingIndex" :disabled="refreshingDdl">
          刷新索引统计
        </el-button>
      </div>
    </div>

    <el-alert v-if="connectionStatus" :type="connectionStatus.type" :closable="true" show-icon style="margin-bottom: 16px">
      <template #title>
        {{ connectionStatus.message }}
      </template>
    </el-alert>

    <el-card v-if="lastResult" class="result-card" shadow="hover">
      <template #header>
        <div class="result-header">
          <span>{{ lastResult.title }}</span>
          <el-tag :type="lastResult.success ? 'success' : 'danger'" size="small">
            {{ lastResult.success ? '成功' : '失败' }}
          </el-tag>
        </div>
      </template>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="总表数">{{ lastResult.total }}</el-descriptions-item>
        <el-descriptions-item label="成功">{{ lastResult.successCount }}</el-descriptions-item>
        <el-descriptions-item label="失败">{{ lastResult.failed }}</el-descriptions-item>
        <el-descriptions-item label="有更新">
          <el-tag type="warning" size="small">{{ lastResult.updated }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="lastResult.updatedTables && lastResult.updatedTables.length > 0" class="updated-tables">
        <p style="margin: 12px 0 8px; font-weight: 500; color: #606266;">更新的表：</p>
        <el-tag v-for="t in lastResult.updatedTables" :key="t" size="small" style="margin: 2px 4px;">
          {{ t }}
        </el-tag>
      </div>
    </el-card>

    <el-card class="table-list-card" shadow="hover">
      <template #header>
        <div class="list-header">
          <span>已采集的表 ({{ tables.length }})</span>
          <el-input v-model="searchText" placeholder="搜索表名..." clearable style="width: 260px" />
        </div>
      </template>
      <div class="table-tags">
        <el-tag
          v-for="t in filteredTables"
          :key="t"
          size="small"
          class="table-tag"
        >
          {{ t }}
        </el-tag>
        <el-empty v-if="filteredTables.length === 0" description="暂无表结构数据" :image-size="60" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tableMetaApi } from '../api'

const tables = ref<string[]>([])
const searchText = ref('')
const testing = ref(false)
const refreshingDdl = ref(false)
const refreshingIndex = ref(false)
const connectionStatus = ref<{ type: 'success' | 'error'; message: string } | null>(null)
const lastResult = ref<{
  title: string
  success: boolean
  total: number
  successCount: number
  failed: number
  updated: number
  updatedTables: string[]
} | null>(null)

const filteredTables = computed(() => {
  if (!searchText.value) return tables.value
  const keyword = searchText.value.toLowerCase()
  return tables.value.filter(t => t.toLowerCase().includes(keyword))
})

const loadTables = async () => {
  try {
    const res: any = await tableMetaApi.list()
    tables.value = res.data ?? []
  } catch {
    tables.value = []
  }
}

const testConnection = async () => {
  testing.value = true
  connectionStatus.value = null
  try {
    const res: any = await tableMetaApi.connectionTest()
    if (res.code === 200) {
      connectionStatus.value = {
        type: 'success',
        message: `连接成功，数据库共 ${res.data.tableCount} 张表`
      }
    } else {
      connectionStatus.value = { type: 'error', message: res.message || '连接失败' }
    }
  } catch (e: any) {
    connectionStatus.value = { type: 'error', message: '连接失败：' + (e.message || '未知错误') }
  } finally {
    testing.value = false
  }
}

const confirmRefreshDdl = async () => {
  try {
    await ElMessageBox.confirm(
      `将从远程平台查询所有已采集表（${tables.value.length} 张）的最新 DDL 并更新本地文件，该操作耗时较长，确定继续？`,
      '确认刷新 DDL',
      { confirmButtonText: '确定刷新', cancelButtonText: '取消', type: 'warning' }
    )
    refreshingDdl.value = true
    lastResult.value = null
    ElMessage.info('正在刷新 DDL，请耐心等待...')
    const res: any = await tableMetaApi.refreshDdl()
    if (res.code === 200) {
      lastResult.value = {
        title: '刷新 DDL 结果',
        success: true,
        total: res.data.total,
        successCount: res.data.success,
        failed: res.data.failed,
        updated: res.data.updated,
        updatedTables: res.data.updatedTables ?? []
      }
      ElMessage.success(`DDL 刷新完成：${res.data.updated} 张表有更新`)
    } else {
      lastResult.value = { title: '刷新 DDL 结果', success: false, total: 0, successCount: 0, failed: 0, updated: 0, updatedTables: [] }
      ElMessage.error(res.message || '刷新失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('刷新 DDL 失败：' + (e.message || '未知错误'))
    }
  } finally {
    refreshingDdl.value = false
  }
}

const confirmRefreshIndex = async () => {
  try {
    await ElMessageBox.confirm(
      `将从远程平台查询所有已采集表（${tables.value.length} 张）的索引统计信息（区分度、基数等）并更新到本地文件，该操作耗时较长，确定继续？`,
      '确认刷新索引统计',
      { confirmButtonText: '确定刷新', cancelButtonText: '取消', type: 'warning' }
    )
    refreshingIndex.value = true
    lastResult.value = null
    ElMessage.info('正在刷新索引统计，请耐心等待...')
    const res: any = await tableMetaApi.refreshIndexStats()
    if (res.code === 200) {
      lastResult.value = {
        title: '刷新索引统计结果',
        success: true,
        total: res.data.total,
        successCount: res.data.success,
        failed: res.data.failed,
        updated: res.data.updated,
        updatedTables: res.data.updatedTables ?? []
      }
      ElMessage.success(`索引统计刷新完成：${res.data.updated} 张表有更新`)
    } else {
      lastResult.value = { title: '刷新索引统计结果', success: false, total: 0, successCount: 0, failed: 0, updated: 0, updatedTables: [] }
      ElMessage.error(res.message || '刷新失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('刷新索引统计失败：' + (e.message || '未知错误'))
    }
  } finally {
    refreshingIndex.value = false
  }
}

onMounted(loadTables)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.result-card {
  margin-bottom: 16px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-list-card {
  margin-bottom: 16px;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 400px;
  overflow-y: auto;
}

.table-tag {
  cursor: default;
}
</style>
