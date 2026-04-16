<template>
  <div class="analysis">
    <h2>SQL 分析结果</h2>

    <el-form inline class="filter-form">
      <el-form-item label="所属项目">
        <el-select v-model="filters.projectId" clearable placeholder="全部项目" @change="loadData">
          <el-option
            v-for="p in projects"
            :key="p.id"
            :label="p.projectName"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="风险等级">
        <el-select v-model="filters.riskLevel" clearable placeholder="全部">
          <el-option label="P0 - 紧急" value="P0" />
          <el-option label="P1 - 高危" value="P1" />
          <el-option label="P2 - 中危" value="P2" />
          <el-option label="P3 - 低危" value="P3" />
          <el-option label="P4 - 安全" value="P4" />
        </el-select>
      </el-form-item>
      <el-form-item label="处理状态">
        <el-select v-model="filters.handleStatus" clearable placeholder="全部">
          <el-option label="AI已分析" value="ANALYZED" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已修复" value="FIXED" />
          <el-option label="已忽略" value="IGNORED" />
          <el-option label="误报" value="FALSE_POSITIVE" />
        </el-select>
      </el-form-item>
      <el-form-item label="分析时间">
        <el-date-picker
          v-model="filters.timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          :shortcuts="timeShortcuts"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">搜索</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="records" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="sqlRecordId" label="SQL记录" width="100">
        <template #default="{ row }">
          <el-link v-if="row.sqlRecordId" type="primary" :underline="false" @click="goToSqlRecord(row.sqlRecordId)">
            #{{ row.sqlRecordId }}
          </el-link>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="finalRiskLevel" label="风险等级" width="100">
        <template #default="{ row }">
          <el-tooltip :content="riskDescription(row.finalRiskLevel)" placement="top">
            <el-tag :type="riskTagType(row.finalRiskLevel)" size="small">
              {{ row.finalRiskLevel }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="aiRiskLevel" label="AI 判定" width="100" />
      <el-table-column prop="aiEstimatedExecTimeMs" label="预估耗时(ms)" width="120" />
      <el-table-column prop="aiEstimatedScanRows" label="预估扫描行数" width="130" />
      <el-table-column prop="owner" label="负责人" width="120">
        <template #default="{ row }">
          <span>{{ row.owner ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="handleStatus" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.handleStatus)" size="small">{{ statusLabel(row.handleStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="分析时间" min-width="180" sortable>
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="viewDetail(row)">详情</el-button>
          <el-button size="small" link type="warning" @click="remindFromList(row)" :loading="remindingId === row.id">提醒</el-button>
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

    <el-drawer v-model="drawerVisible" title="SQL 分析详情" size="65%">
      <template v-if="currentDetail">
        <div class="detail-actions">
          <span class="status-label">当前状态：</span>
          <el-tag :type="statusTagType(currentDetail.analysis?.handleStatus)" size="default">
            {{ statusLabel(currentDetail.analysis?.handleStatus) }}
          </el-tag>
          <el-divider direction="vertical" />
          <span class="status-label">修改状态：</span>
          <el-button-group>
            <el-button
              v-for="s in statusOptions"
              :key="s.value"
              :type="currentDetail.analysis?.handleStatus === s.value ? 'primary' : 'default'"
              size="small"
              @click="handleStatusChange(s.value)"
              :disabled="currentDetail.analysis?.handleStatus === s.value"
            >{{ s.label }}</el-button>
          </el-button-group>
        </div>

        <h4>SQL 来源</h4>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="所属项目">
            <code>{{ currentDetail.projectName ?? '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="文件路径">
            <code>{{ currentDetail.sourceFile ?? '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="定位">
            <code>{{ currentDetail.sourceLocation ?? '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="SQL 类型">{{ currentDetail.sqlType ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源类型">{{ currentDetail.sourceType ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="负责人">
            <span>{{ currentDetail.owner ?? '-' }}</span>
            <el-button
              v-if="currentDetail.analysis?.sqlRecordId"
              size="small" type="warning" link style="margin-left: 8px"
              @click="remindFromDetail"
              :loading="remindingId === currentDetail.analysis?.id"
            >提醒开发人员</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="SQL 记录">
            <el-link
              v-if="currentDetail.analysis?.sqlRecordId"
              type="primary"
              @click="goToSqlRecord(currentDetail.analysis.sqlRecordId)"
            >
              #{{ currentDetail.analysis.sqlRecordId }} — 查看详情
            </el-link>
            <span v-else>-</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4>原始 SQL</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ currentDetail.sqlText ?? '暂无' }}</pre>
        </el-card>

        <h4>标准化 SQL</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ currentDetail.sqlNormalized ?? '暂无' }}</pre>
        </el-card>

        <h4>AI 分析报告</h4>
        <el-card shadow="never">
          <pre class="ai-report">{{ currentDetail.analysis?.aiAnalysis ?? '暂无 AI 分析' }}</pre>
        </el-card>

        <h4>索引建议</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ formatJson(currentDetail.analysis?.aiIndexSuggestion) }}</pre>
        </el-card>

        <h4>SQL 重写建议</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ formatJson(currentDetail.analysis?.aiRewriteSuggestion) }}</pre>
        </el-card>

        <h4>表结构元数据（DDL）</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ currentDetail.tableMetaContext ?? '未匹配到表结构文件' }}</pre>
        </el-card>

        <h4 v-if="currentDetail.analysis?.handleNote">处理备注</h4>
        <el-card v-if="currentDetail.analysis?.handleNote" shadow="never">
          <pre class="ai-report">{{ currentDetail.analysis.handleNote }}</pre>
        </el-card>
      </template>
    </el-drawer>

    <el-dialog v-model="noteDialogVisible" title="添加处理备注" width="400px">
      <el-input v-model="handleNote" type="textarea" :rows="3" placeholder="请输入处理备注（可选）" />
      <template #footer>
        <el-button @click="noteDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmStatusChange">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { analysisApi, projectApi, sqlRecordApi } from '../api'
import { formatTime } from '../utils/format'

const router = useRouter()

const records = ref<any[]>([])
const projects = ref<any[]>([])
const page = ref(1)
const total = ref(0)
const filters = ref<{
  projectId: number | null
  riskLevel: string
  handleStatus: string
  timeRange: [string, string] | null
}>({
  projectId: null,
  riskLevel: '',
  handleStatus: '',
  timeRange: null
})
const drawerVisible = ref(false)
const currentDetail = ref<any>(null)
const noteDialogVisible = ref(false)
const handleNote = ref('')
const pendingStatus = ref('')
const remindingId = ref<number | null>(null)

const statusOptions = [
  { value: 'ANALYZED', label: 'AI已分析' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'FIXED', label: '已修复' },
  { value: 'IGNORED', label: '已忽略' },
  { value: 'FALSE_POSITIVE', label: '误报' }
]

const timeShortcuts = [
  {
    text: '最近1天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 1)
      return [start, end]
    }
  },
  {
    text: '最近7天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 7)
      return [start, end]
    }
  },
  {
    text: '最近30天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 30)
      return [start, end]
    }
  }
]

const riskLevelMap: Record<string, { type: string; desc: string }> = {
  P0: { type: 'danger', desc: '紧急 - 必定慢SQL，需立即修复' },
  P1: { type: 'danger', desc: '高危 - 大概率慢SQL，建议尽快优化' },
  P2: { type: 'warning', desc: '中危 - 存在性能风险，建议优化' },
  P3: { type: 'info', desc: '低危 - 轻微风险，可择期优化' },
  P4: { type: 'success', desc: '安全 - 无明显性能风险' },
}

const riskTagType = (level: string) => riskLevelMap[level]?.type ?? 'info'
const riskDescription = (level: string) => riskLevelMap[level]?.desc ?? level

const statusTagType = (status: string) => {
  switch (status) {
    case 'ANALYZED': return 'warning'
    case 'CONFIRMED': return ''
    case 'FIXED': return 'success'
    case 'IGNORED': return 'info'
    case 'FALSE_POSITIVE': return 'info'
    default: return 'warning'
  }
}

const statusLabel = (status: string) => {
  switch (status) {
    case 'ANALYZED': return 'AI已分析'
    case 'PENDING': return '待处理'
    case 'CONFIRMED': return '已确认'
    case 'FIXED': return '已修复'
    case 'IGNORED': return '已忽略'
    case 'FALSE_POSITIVE': return '误报'
    default: return status
  }
}

const formatJson = (jsonStr: string | null) => {
  if (!jsonStr) return '暂无'
  try {
    const arr = JSON.parse(jsonStr)
    if (Array.isArray(arr)) {
      return arr.join('\n\n')
    }
    return jsonStr
  } catch {
    return jsonStr
  }
}

const resetFilters = () => {
  filters.value = { projectId: null, riskLevel: '', handleStatus: '', timeRange: null }
  page.value = 1
  loadData()
}

const loadData = async () => {
  try {
    const params: Record<string, any> = { current: page.value, size: 10 }
    if (filters.value.projectId) params.projectId = filters.value.projectId
    if (filters.value.riskLevel) params.riskLevel = filters.value.riskLevel
    if (filters.value.handleStatus) params.handleStatus = filters.value.handleStatus
    if (filters.value.timeRange && filters.value.timeRange.length === 2) {
      params.startTime = filters.value.timeRange[0]
      params.endTime = filters.value.timeRange[1]
    }
    const res: any = await analysisApi.page(params)
    records.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch {
    // handle error
  }
}

const loadProjects = async () => {
  try {
    const res: any = await projectApi.list()
    projects.value = res.data ?? []
  } catch {
    // handle error
  }
}

const viewDetail = async (row: any) => {
  try {
    const res: any = await analysisApi.detail(row.id)
    currentDetail.value = res.data
    drawerVisible.value = true
  } catch {
    // handle error
  }
}

const handleStatusChange = (status: string) => {
  pendingStatus.value = status
  handleNote.value = ''
  noteDialogVisible.value = true
}

const confirmStatusChange = async () => {
  if (!currentDetail.value?.analysis?.id) return
  try {
    await analysisApi.handle(currentDetail.value.analysis.id, pendingStatus.value, handleNote.value || undefined)
    currentDetail.value.analysis.handleStatus = pendingStatus.value
    if (handleNote.value) {
      currentDetail.value.analysis.handleNote = handleNote.value
    }
    noteDialogVisible.value = false
    ElMessage.success('状态更新成功')
    loadData()
  } catch {
    ElMessage.error('状态更新失败')
  }
}

const remindFromList = async (row: any) => {
  if (!row?.sqlRecordId) {
    ElMessage.warning('该分析记录没有关联的 SQL 记录')
    return
  }
  try {
    await ElMessageBox.confirm('确认通过企业微信提醒负责人处理此 SQL？', '提醒确认', {
      confirmButtonText: '发送提醒', cancelButtonText: '取消', type: 'info'
    })
  } catch { return }

  remindingId.value = row.id
  try {
    const res: any = await sqlRecordApi.remind(row.sqlRecordId)
    ElMessage.success(res.data || '提醒已发送')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '提醒发送失败')
  } finally {
    remindingId.value = null
  }
}

const remindFromDetail = async () => {
  const sqlRecordId = currentDetail.value?.analysis?.sqlRecordId
  if (!sqlRecordId) return
  const owner = currentDetail.value?.owner ?? '负责人'
  try {
    await ElMessageBox.confirm(`确认通过企业微信提醒 ${owner} 处理此 SQL？`, '提醒确认', {
      confirmButtonText: '发送提醒', cancelButtonText: '取消', type: 'info'
    })
  } catch { return }

  remindingId.value = currentDetail.value?.analysis?.id
  try {
    const res: any = await sqlRecordApi.remind(sqlRecordId)
    ElMessage.success(res.data || '提醒已发送')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '提醒发送失败')
  } finally {
    remindingId.value = null
  }
}

const goToSqlRecord = (recordId: number) => {
  router.push({ name: 'SqlRecords', query: { recordId: String(recordId) } })
}

onMounted(() => {
  loadProjects()
  loadData()
})
</script>

<style scoped>
.filter-form {
  margin-bottom: 16px;
}
.detail-actions {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 4px;
}
.status-label {
  font-size: 14px;
  color: #606266;
  margin-right: 8px;
}
.ai-report {
  white-space: pre-wrap;
  word-wrap: break-word;
  font-size: 14px;
  line-height: 1.6;
}
.sql-block {
  white-space: pre-wrap;
  word-wrap: break-word;
  font-size: 13px;
  line-height: 1.5;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
}
h4 {
  margin-top: 20px;
  margin-bottom: 8px;
}
</style>
