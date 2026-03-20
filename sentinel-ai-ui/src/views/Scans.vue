<template>
  <div class="scans">
    <h2>扫描历史</h2>
    <el-table :data="batches" stripe>
      <el-table-column prop="id" label="批次ID" width="80" />
      <el-table-column prop="projectId" label="项目ID" width="80" />
      <el-table-column prop="scanType" label="扫描类型" width="120">
        <template #default="{ row }">
          <el-tag :type="row.scanType === 'FULL' ? 'danger' : 'success'" size="small">
            {{ row.scanType === 'FULL' ? '全量' : '增量' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Commit 范围" width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.fromCommit">
            {{ row.fromCommit?.substring(0, 8) }}..{{ row.toCommit?.substring(0, 8) }}
          </span>
          <span v-else-if="row.toCommit">
            {{ row.toCommit?.substring(0, 8) }} (全量)
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="totalSqlCount" label="SQL 总数" width="100" />
      <el-table-column prop="newSqlCount" label="新增" width="80" />
      <el-table-column prop="changedSqlCount" label="变更" width="80" />
      <el-table-column prop="removedSqlCount" label="移除" width="80" />
      <el-table-column prop="riskSqlCount" label="风险" width="80" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="scanDurationMs" label="耗时(ms)" width="100" />
      <el-table-column label="扫描时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
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
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { scanApi } from '../api'
import { formatTime } from '../utils/format'

const batches = ref<any[]>([])
const page = ref(1)
const total = ref(0)

const statusTagType = (status: string) => {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'RUNNING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

const loadData = async () => {
  try {
    const res: any = await scanApi.history({ current: page.value, size: 10 })
    batches.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch {
    // handle error
  }
}

onMounted(loadData)
</script>
