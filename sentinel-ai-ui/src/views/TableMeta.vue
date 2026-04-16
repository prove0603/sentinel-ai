<template>
  <div class="table-meta">
    <div class="page-header">
      <h2>表结构管理</h2>
      <div class="header-actions">
        <el-button @click="testConnection" :loading="testing" size="small">
          测试平台连接
        </el-button>
        <el-button type="success" @click="handleInitFromFiles" :loading="initializing" size="small">
          从文件初始化
        </el-button>
        <el-button type="primary" @click="confirmRefreshDdl" :loading="refreshingDdl" size="small">
          批量刷新 DDL
        </el-button>
        <el-button type="warning" @click="confirmRefreshIndex" :loading="refreshingIndex" size="small">
          批量刷新索引
        </el-button>
        <el-button @click="showAddDialog" size="small">
          手动添加
        </el-button>
      </div>
    </div>

    <el-alert v-if="connectionStatus" :type="connectionStatus.type" :closable="true" show-icon style="margin-bottom: 16px">
      <template #title>{{ connectionStatus.message }}</template>
    </el-alert>

    <el-alert v-if="batchResult" :type="batchResult.success ? 'success' : 'warning'" :closable="true" show-icon style="margin-bottom: 16px">
      <template #title>{{ batchResult.title }}</template>
      <template #default>
        <span>总计: {{ batchResult.total }} | 成功: {{ batchResult.successCount }} | 失败: {{ batchResult.failed }}</span>
        <span v-if="batchResult.updated !== undefined"> | 更新: {{ batchResult.updated }}</span>
      </template>
    </el-alert>

    <!-- 搜索栏 -->
    <el-card shadow="never" style="margin-bottom: 16px">
      <el-form :inline="true" @submit.prevent="loadData">
        <el-form-item label="表名">
          <el-input v-model="searchName" placeholder="模糊搜索表名" clearable @clear="loadData" style="width: 260px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">搜索</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never">
      <el-table :data="tableData" v-loading="loading" stripe style="width: 100%" @row-click="showDetail">
        <el-table-column prop="tableName" label="表名" min-width="240" show-overflow-tooltip />
        <el-table-column prop="estimatedRows" label="预估行数" width="120" align="right">
          <template #default="{ row }">
            {{ row.estimatedRows ? row.estimatedRows.toLocaleString() : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="DDL" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.ddlText ? 'success' : 'info'" size="small">
              {{ row.ddlText ? '有' : '无' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="索引统计" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.indexStats ? 'success' : 'info'" size="small">
              {{ row.indexStats ? '有' : '无' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170">
          <template #default="{ row }">
            {{ row.updateTime ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="showEditDialog(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click.stop="handleRefreshSingle(row)">刷新</el-button>
            <el-popconfirm title="确认删除此表结构？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button link type="danger" size="small" @click.stop>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.current"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @change="loadData"
      />
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" :title="'表结构详情 - ' + (detailRow?.tableName ?? '')" width="800px" top="5vh">
      <div v-if="detailRow">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="表名">{{ detailRow.tableName }}</el-descriptions-item>
          <el-descriptions-item label="预估行数">{{ detailRow.estimatedRows?.toLocaleString() ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detailRow.updateTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detailRow.createTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-tabs>
          <el-tab-pane label="DDL">
            <pre class="code-block">{{ detailRow.ddlText || '暂无 DDL 数据' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="索引统计">
            <pre class="code-block">{{ detailRow.indexStats || '暂无索引统计数据' }}</pre>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="formVisible" :title="formMode === 'add' ? '新增表结构' : '编辑表结构'" width="700px" top="5vh">
      <el-form :model="formData" label-width="100px">
        <el-form-item label="表名" required>
          <el-input v-model="formData.tableName" :disabled="formMode === 'edit'" placeholder="如 t_user_info" />
        </el-form-item>
        <el-form-item label="预估行数">
          <el-input-number v-model="formData.estimatedRows" :min="0" />
        </el-form-item>
        <el-form-item label="DDL">
          <el-input v-model="formData.ddlText" type="textarea" :rows="10" placeholder="CREATE TABLE ..." />
        </el-form-item>
        <el-form-item label="索引统计">
          <el-input v-model="formData.indexStats" type="textarea" :rows="6" placeholder="索引统计信息（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="handleFormSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tableMetaApi } from '../api'

interface TableMetaRow {
  id: number
  tableName: string
  ddlText?: string
  estimatedRows?: number
  indexInfo?: string
  indexStats?: string
  createTime?: string
  updateTime?: string
}

const loading = ref(false)
const tableData = ref<TableMetaRow[]>([])
const searchName = ref('')
const pagination = reactive({ current: 1, size: 20, total: 0 })

const testing = ref(false)
const initializing = ref(false)
const refreshingDdl = ref(false)
const refreshingIndex = ref(false)
const submitting = ref(false)

const connectionStatus = ref<{ type: 'success' | 'error'; message: string } | null>(null)
const batchResult = ref<{ title: string; success: boolean; total: number; successCount: number; failed: number; updated?: number } | null>(null)

const detailVisible = ref(false)
const detailRow = ref<TableMetaRow | null>(null)

const formVisible = ref(false)
const formMode = ref<'add' | 'edit'>('add')
const editingId = ref<number>(0)
const formData = reactive({
  tableName: '',
  ddlText: '',
  estimatedRows: 0,
  indexStats: ''
})

const loadData = async () => {
  loading.value = true
  try {
    const res: any = await tableMetaApi.page({
      tableName: searchName.value || undefined,
      current: pagination.current,
      size: pagination.size
    })
    if (res.code === 200) {
      tableData.value = res.data.records ?? []
      pagination.total = res.data.total ?? 0
    }
  } catch {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const showDetail = (row: TableMetaRow) => {
  detailRow.value = row
  detailVisible.value = true
}

const showAddDialog = () => {
  formMode.value = 'add'
  formData.tableName = ''
  formData.ddlText = ''
  formData.estimatedRows = 0
  formData.indexStats = ''
  formVisible.value = true
}

const showEditDialog = (row: TableMetaRow) => {
  formMode.value = 'edit'
  editingId.value = row.id
  formData.tableName = row.tableName
  formData.ddlText = row.ddlText ?? ''
  formData.estimatedRows = row.estimatedRows ?? 0
  formData.indexStats = row.indexStats ?? ''
  formVisible.value = true
}

const handleFormSubmit = async () => {
  if (!formData.tableName) {
    ElMessage.warning('请输入表名')
    return
  }
  submitting.value = true
  try {
    if (formMode.value === 'add') {
      const res: any = await tableMetaApi.create(formData)
      res.code === 200 ? ElMessage.success('添加成功') : ElMessage.error(res.message)
    } else {
      const res: any = await tableMetaApi.update(editingId.value, formData)
      res.code === 200 ? ElMessage.success('更新成功') : ElMessage.error(res.message)
    }
    formVisible.value = false
    loadData()
  } catch {
    ElMessage.error('操作失败')
  } finally {
    submitting.value = false
  }
}

const handleDelete = async (id: number) => {
  try {
    await tableMetaApi.delete(id)
    ElMessage.success('删除成功')
    loadData()
  } catch {
    ElMessage.error('删除失败')
  }
}

const handleRefreshSingle = async (row: TableMetaRow) => {
  try {
    await ElMessageBox.confirm(
      `从 DBA 平台刷新 ${row.tableName} 的 DDL 和索引？`,
      '确认刷新',
      { type: 'info' }
    )
    ElMessage.info('正在刷新...')
    const res: any = await tableMetaApi.refreshSingle(row.tableName)
    if (res.code === 200) {
      ElMessage.success('刷新成功')
      loadData()
    } else {
      ElMessage.error(res.message || '刷新失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('刷新失败: ' + (e.message || ''))
  }
}

const handleInitFromFiles = async () => {
  try {
    await ElMessageBox.confirm(
      '将从 table-meta 目录读取所有 .sql 文件并导入到数据库，已存在的将被更新。确定继续？',
      '从文件初始化',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    initializing.value = true
    batchResult.value = null
    ElMessage.info('正在初始化，请等待...')
    const res: any = await tableMetaApi.initFromFiles()
    if (res.code === 200) {
      batchResult.value = {
        title: '文件初始化完成',
        success: true,
        total: res.data.totalFiles,
        successCount: (res.data.created ?? 0) + (res.data.updated ?? 0),
        failed: res.data.skipped ?? 0
      }
      ElMessage.success(`初始化完成: 新增 ${res.data.created}，更新 ${res.data.updated}`)
      loadData()
    } else {
      ElMessage.error(res.message || '初始化失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('初始化失败: ' + (e.message || ''))
  } finally {
    initializing.value = false
  }
}

const testConnection = async () => {
  testing.value = true
  connectionStatus.value = null
  try {
    const res: any = await tableMetaApi.connectionTest()
    if (res.code === 200) {
      connectionStatus.value = { type: 'success', message: `连接成功，数据库共 ${res.data.tableCount} 张表` }
    } else {
      connectionStatus.value = { type: 'error', message: res.message || '连接失败' }
    }
  } catch (e: any) {
    connectionStatus.value = { type: 'error', message: '连接失败: ' + (e.message || '') }
  } finally {
    testing.value = false
  }
}

const confirmRefreshDdl = async () => {
  try {
    await ElMessageBox.confirm(
      '将从 DBA 平台查询所有已采集表的最新 DDL 并同步到数据库，耗时较长，确定继续？',
      '批量刷新 DDL',
      { type: 'warning' }
    )
    refreshingDdl.value = true
    batchResult.value = null
    ElMessage.info('正在刷新 DDL...')
    const res: any = await tableMetaApi.refreshDdl()
    if (res.code === 200) {
      batchResult.value = {
        title: '批量刷新 DDL 完成',
        success: true,
        total: res.data.total,
        successCount: res.data.success,
        failed: res.data.failed,
        updated: res.data.updated
      }
      ElMessage.success(`DDL 刷新完成: ${res.data.updated} 张有更新`)
      loadData()
    } else {
      ElMessage.error(res.message || '刷新失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('刷新失败: ' + (e.message || ''))
  } finally {
    refreshingDdl.value = false
  }
}

const confirmRefreshIndex = async () => {
  try {
    await ElMessageBox.confirm(
      '将从 DBA 平台查询所有已采集表的索引统计信息并同步到数据库，耗时较长，确定继续？',
      '批量刷新索引统计',
      { type: 'warning' }
    )
    refreshingIndex.value = true
    batchResult.value = null
    ElMessage.info('正在刷新索引统计...')
    const res: any = await tableMetaApi.refreshIndexStats()
    if (res.code === 200) {
      batchResult.value = {
        title: '批量刷新索引统计完成',
        success: true,
        total: res.data.total,
        successCount: res.data.success,
        failed: res.data.failed,
        updated: res.data.updated
      }
      ElMessage.success(`索引统计刷新完成: ${res.data.updated} 张有更新`)
      loadData()
    } else {
      ElMessage.error(res.message || '刷新失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('刷新失败: ' + (e.message || ''))
  } finally {
    refreshingIndex.value = false
  }
}

onMounted(loadData)
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
  flex-wrap: wrap;
}

.code-block {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px 16px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 500px;
  overflow-y: auto;
  margin: 0;
}
</style>
