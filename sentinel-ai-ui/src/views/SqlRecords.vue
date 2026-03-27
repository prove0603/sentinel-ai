<template>
  <div class="sql-records">
    <h2>SQL 记录</h2>

    <el-form inline class="filter-form">
      <el-form-item label="所属项目">
        <el-select v-model="filters.projectId" clearable placeholder="全部项目" @change="handleFilterChange">
          <el-option
            v-for="p in projects"
            :key="p.id"
            :label="p.projectName"
            :value="p.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="SQL 类型">
        <el-select v-model="filters.sqlType" clearable placeholder="全部" @change="handleFilterChange">
          <el-option label="SELECT" value="SELECT" />
          <el-option label="INSERT" value="INSERT" />
          <el-option label="UPDATE" value="UPDATE" />
          <el-option label="DELETE" value="DELETE" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源类型">
        <el-select v-model="filters.sourceType" clearable placeholder="全部" @change="handleFilterChange">
          <el-option label="Mapper XML" value="MAPPER_XML" />
          <el-option label="QueryWrapper" value="QUERY_WRAPPER" />
          <el-option label="LambdaWrapper" value="LAMBDA_WRAPPER" />
          <el-option label="注解 SQL" value="ANNOTATION" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadData">搜索</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="records" stripe border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="sqlType" label="SQL 类型" width="100">
        <template #default="{ row }">
          <el-tag :type="sqlTypeTag(row.sqlType)" size="small">{{ row.sqlType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceType" label="来源类型" width="140">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ sourceTypeLabel(row.sourceType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceFile" label="源文件" min-width="240" show-overflow-tooltip />
      <el-table-column prop="sourceLocation" label="定位" width="200" show-overflow-tooltip />
      <el-table-column prop="sqlText" label="SQL" min-width="300">
        <template #default="{ row }">
          <div class="sql-preview" @click="viewDetail(row)">{{ truncateSql(row.sqlText) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170" sortable>
        <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="viewDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="pageSize"
      :total="total"
      layout="total, prev, pager, next, jumper"
      @current-change="loadData"
      style="margin-top: 16px"
    />

    <el-drawer v-model="drawerVisible" title="SQL 详情" size="60%">
      <template v-if="currentRecord">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ID">{{ currentRecord.id }}</el-descriptions-item>
          <el-descriptions-item label="SQL 类型">
            <el-tag :type="sqlTypeTag(currentRecord.sqlType)" size="small">{{ currentRecord.sqlType }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源类型">{{ sourceTypeLabel(currentRecord.sourceType) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ currentRecord.status === 1 ? '活跃' : '已删除' }}</el-descriptions-item>
          <el-descriptions-item label="源文件" :span="2">
            <code>{{ currentRecord.sourceFile }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="定位" :span="2">
            <code>{{ currentRecord.sourceLocation }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(currentRecord.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(currentRecord.updateTime) }}</el-descriptions-item>
        </el-descriptions>

        <h4>原始 SQL</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ currentRecord.sqlText }}</pre>
        </el-card>

        <h4>标准化 SQL</h4>
        <el-card shadow="never">
          <pre class="sql-block">{{ currentRecord.sqlNormalized ?? '暂无' }}</pre>
        </el-card>

        <el-divider />
        <div class="ai-action-bar">
          <el-button type="primary" :loading="analyzing" @click="triggerAiAnalysis">
            {{ analyzing ? 'AI 分析中...' : '触发 AI 分析' }}
          </el-button>
          <span v-if="analyzing" class="ai-tip">正在调用 AI 模型分析，请稍候...</span>
        </div>

        <template v-if="analysisResult">
          <h4>AI 分析报告</h4>
          <el-descriptions :column="3" border size="small" style="margin-bottom: 12px;">
            <el-descriptions-item label="风险等级">
              <el-tag :type="riskTagType(analysisResult.analysis?.finalRiskLevel)" size="small">
                {{ analysisResult.analysis?.finalRiskLevel ?? '-' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="预估扫描行数">{{ analysisResult.analysis?.aiEstimatedScanRows ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="预估耗时(ms)">{{ analysisResult.analysis?.aiEstimatedExecTimeMs ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="AI 模型">{{ analysisResult.analysis?.aiModel ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="Token 消耗">{{ analysisResult.analysis?.aiTokensUsed ?? '-' }}</el-descriptions-item>
          </el-descriptions>

          <h4>综合分析</h4>
          <el-card shadow="never">
            <pre class="ai-report">{{ analysisResult.analysis?.aiAnalysis ?? '暂无' }}</pre>
          </el-card>

          <h4>索引建议</h4>
          <el-card shadow="never">
            <pre class="sql-block">{{ formatJson(analysisResult.analysis?.aiIndexSuggestion) }}</pre>
          </el-card>

          <h4>SQL 重写建议</h4>
          <el-card shadow="never">
            <pre class="sql-block">{{ formatJson(analysisResult.analysis?.aiRewriteSuggestion) }}</pre>
          </el-card>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { sqlRecordApi, projectApi, analysisApi } from '../api'
import { formatTime } from '../utils/format'

const records = ref<any[]>([])
const projects = ref<any[]>([])
const page = ref(1)
const pageSize = 20
const total = ref(0)
const drawerVisible = ref(false)
const currentRecord = ref<any>(null)
const analyzing = ref(false)
const analysisResult = ref<any>(null)
const filters = ref<{
  projectId: number | null
  sqlType: string
  sourceType: string
}>({
  projectId: null,
  sqlType: '',
  sourceType: ''
})

const sqlTypeTag = (type: string) => {
  switch (type) {
    case 'SELECT': return 'primary'
    case 'INSERT': return 'success'
    case 'UPDATE': return 'warning'
    case 'DELETE': return 'danger'
    default: return 'info'
  }
}

const sourceTypeLabel = (type: string) => {
  switch (type) {
    case 'MAPPER_XML': return 'Mapper XML'
    case 'QUERY_WRAPPER': return 'QueryWrapper'
    case 'LAMBDA_WRAPPER': return 'LambdaWrapper'
    case 'ANNOTATION': return '注解 SQL'
    default: return type
  }
}

const truncateSql = (sql: string) => {
  if (!sql) return ''
  return sql.length > 120 ? sql.substring(0, 120) + '...' : sql
}

const handleFilterChange = () => {
  page.value = 1
  loadData()
}

const resetFilters = () => {
  filters.value = { projectId: null, sqlType: '', sourceType: '' }
  page.value = 1
  loadData()
}

const loadData = async () => {
  try {
    const params: Record<string, any> = { current: page.value, size: pageSize }
    if (filters.value.projectId) params.projectId = filters.value.projectId
    if (filters.value.sqlType) params.sqlType = filters.value.sqlType
    if (filters.value.sourceType) params.sourceType = filters.value.sourceType
    const res: any = await sqlRecordApi.page(params)
    records.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch {
    records.value = []
    total.value = 0
  }
}

const viewDetail = (row: any) => {
  currentRecord.value = row
  analysisResult.value = null
  drawerVisible.value = true
}

const triggerAiAnalysis = async () => {
  if (!currentRecord.value?.id) return
  analyzing.value = true
  analysisResult.value = null
  try {
    const res: any = await analysisApi.reanalyze(currentRecord.value.id)
    analysisResult.value = res.data
    ElMessage.success('AI 分析完成')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || 'AI 分析失败')
  } finally {
    analyzing.value = false
  }
}

const riskTagType = (level: string) => {
  switch (level) {
    case 'P0': case 'P1': return 'danger'
    case 'P2': return 'warning'
    case 'P3': return 'info'
    case 'P4': return 'success'
    default: return 'info'
  }
}

const formatJson = (jsonStr: string | null) => {
  if (!jsonStr) return '暂无'
  try {
    const arr = JSON.parse(jsonStr)
    if (Array.isArray(arr)) return arr.join('\n\n')
    return jsonStr
  } catch {
    return jsonStr
  }
}

const loadProjects = async () => {
  try {
    const res: any = await projectApi.list()
    projects.value = res.data ?? []
  } catch {
    // ignore
  }
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
.sql-preview {
  cursor: pointer;
  color: #606266;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sql-preview:hover {
  color: #409eff;
}
.sql-block {
  white-space: pre-wrap;
  word-wrap: break-word;
  font-size: 13px;
  line-height: 1.5;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
}
.ai-report {
  white-space: pre-wrap;
  word-wrap: break-word;
  font-size: 14px;
  line-height: 1.6;
}
.ai-action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-tip {
  font-size: 13px;
  color: #909399;
}
h4 {
  margin-top: 20px;
  margin-bottom: 8px;
}
</style>
