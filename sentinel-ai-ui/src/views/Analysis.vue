<template>
  <div class="analysis">
    <h2>SQL 分析结果</h2>

    <el-form inline class="filter-form">
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
      <el-form-item>
        <el-button type="primary" @click="loadData">搜索</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="records" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="finalRiskLevel" label="风险等级" width="100">
        <template #default="{ row }">
          <el-tag :type="riskTagType(row.finalRiskLevel)" size="small">
            {{ row.finalRiskLevel }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="aiRiskLevel" label="AI 判定" width="100" />
      <el-table-column prop="aiEstimatedExecTimeMs" label="预估耗时(ms)" width="120" />
      <el-table-column prop="aiEstimatedScanRows" label="预估扫描行数" width="130" />
      <el-table-column prop="handleStatus" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.handleStatus)" size="small">{{ statusLabel(row.handleStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="分析时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="viewDetail(row)">详情</el-button>
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
        <h4>SQL 来源</h4>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="文件路径">
            <code>{{ currentDetail.sourceFile ?? '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="定位">
            <code>{{ currentDetail.sourceLocation ?? '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="SQL 类型">{{ currentDetail.sqlType ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源类型">{{ currentDetail.sourceType ?? '-' }}</el-descriptions-item>
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
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { analysisApi } from '../api'

const records = ref<any[]>([])
const page = ref(1)
const total = ref(0)
const filters = ref({ riskLevel: '', handleStatus: '' })
const drawerVisible = ref(false)
const currentDetail = ref<any>(null)

const riskTagType = (level: string) => {
  switch (level) {
    case 'P0': return 'danger'
    case 'P1': return 'danger'
    case 'P2': return 'warning'
    case 'P3': return 'info'
    case 'P4': return 'success'
    default: return 'info'
  }
}

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

const loadData = async () => {
  try {
    const params: Record<string, any> = { current: page.value, size: 10 }
    if (filters.value.riskLevel) params.riskLevel = filters.value.riskLevel
    if (filters.value.handleStatus) params.handleStatus = filters.value.handleStatus
    const res: any = await analysisApi.page(params)
    records.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
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

onMounted(loadData)
</script>

<style scoped>
.filter-form {
  margin-bottom: 16px;
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
