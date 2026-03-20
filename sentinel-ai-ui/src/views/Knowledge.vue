<template>
  <div class="knowledge">
    <div class="page-header">
      <h2>业务知识库</h2>
      <div class="header-actions">
        <el-tag :type="ragAvailable ? 'success' : 'info'" size="small">
          RAG {{ ragAvailable ? '已启用' : '未启用' }}
        </el-tag>
        <el-button type="warning" size="small" @click="handleReEmbed" :loading="reEmbedding">
          重新向量化
        </el-button>
        <el-button type="primary" @click="openDialog()">新增知识</el-button>
      </div>
    </div>

    <el-form inline class="filter-form">
      <el-form-item label="知识类型">
        <el-select v-model="filters.knowledgeType" clearable placeholder="全部" @change="loadData">
          <el-option v-for="t in knowledgeTypes" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-table :data="records" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="knowledgeType" label="类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="typeTagColor(row.knowledgeType)">{{ typeLabel(row.knowledgeType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="relatedTables" label="关联表" width="200" show-overflow-tooltip />
      <el-table-column prop="embedded" label="已向量化" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.embedded === 1 ? 'success' : 'info'" size="small">
            {{ row.embedded === 1 ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="80" />
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="10"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="loadData"
      style="margin-top: 16px"
    />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑知识' : '新增知识'" width="650px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="知识类型" required>
          <el-select v-model="form.knowledgeType" placeholder="请选择">
            <el-option v-for="t in knowledgeTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="例如：t_order 定时报表允许全表扫描" />
        </el-form-item>
        <el-form-item label="关联表">
          <el-input v-model="form.relatedTables" placeholder="逗号分隔，如 t_order,t_user" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="8"
            placeholder="详细描述业务知识。例如：&#10;- 这个 SQL 是凌晨跑的报表任务，允许慢查询&#10;- 该接口 QPS 约 200，需要走索引&#10;- t_log 的全表扫描用于数据迁移，属于正常操作" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi } from '../api'
import { formatTime } from '../utils/format'

const records = ref<any[]>([])
const page = ref(1)
const total = ref(0)
const filters = ref({ knowledgeType: '' })
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const reEmbedding = ref(false)
const ragAvailable = ref(false)

const form = ref({
  knowledgeType: '',
  title: '',
  content: '',
  relatedTables: ''
})

const knowledgeTypes = [
  { value: 'EXEMPTION', label: '豁免说明' },
  { value: 'BACKGROUND', label: '业务背景' },
  { value: 'EXPERIENCE', label: '优化经验' },
  { value: 'QPS_INFO', label: 'QPS/调用量' },
  { value: 'SLOW_QUERY_NOTE', label: '慢查询备注' }
]

const typeLabel = (type: string) => {
  return knowledgeTypes.find(t => t.value === type)?.label ?? type
}

const typeTagColor = (type: string) => {
  switch (type) {
    case 'EXEMPTION': return 'success'
    case 'BACKGROUND': return ''
    case 'EXPERIENCE': return 'warning'
    case 'QPS_INFO': return 'danger'
    case 'SLOW_QUERY_NOTE': return 'info'
    default: return 'info'
  }
}

const loadData = async () => {
  try {
    const params: Record<string, any> = { current: page.value, size: 10 }
    if (filters.value.knowledgeType) params.knowledgeType = filters.value.knowledgeType
    const res: any = await knowledgeApi.page(params)
    records.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch {
    ElMessage.error('加载失败')
  }
}

const loadStatus = async () => {
  try {
    const res: any = await knowledgeApi.status()
    ragAvailable.value = res.data?.ragAvailable ?? false
  } catch {
    // ignore
  }
}

const openDialog = (row?: any) => {
  if (row) {
    editingId.value = row.id
    form.value = {
      knowledgeType: row.knowledgeType,
      title: row.title,
      content: row.content,
      relatedTables: row.relatedTables ?? ''
    }
  } else {
    editingId.value = null
    form.value = { knowledgeType: '', title: '', content: '', relatedTables: '' }
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  if (!form.value.knowledgeType || !form.value.title || !form.value.content) {
    ElMessage.warning('请填写必填项')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await knowledgeApi.update({ id: editingId.value, ...form.value })
      ElMessage.success('更新成功')
    } else {
      await knowledgeApi.create(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const handleDelete = async (row: any) => {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？`, '确认删除', { type: 'warning' })
    await knowledgeApi.delete(row.id)
    ElMessage.success('已删除')
    loadData()
  } catch {
    // cancelled
  }
}

const handleReEmbed = async () => {
  try {
    await ElMessageBox.confirm('将重新向量化所有知识条目，确认继续？', '重新向量化', { type: 'warning' })
    reEmbedding.value = true
    const res: any = await knowledgeApi.reEmbed(true)
    ElMessage.success(`向量化完成，共处理 ${res.data?.embeddedCount ?? 0} 条`)
    loadData()
  } catch {
    // cancelled
  } finally {
    reEmbedding.value = false
  }
}

onMounted(() => {
  loadStatus()
  loadData()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.filter-form {
  margin-bottom: 16px;
}
</style>
