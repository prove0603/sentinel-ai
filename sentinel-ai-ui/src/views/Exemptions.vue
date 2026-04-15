<template>
  <div class="exemptions">
    <div class="page-header">
      <h2>豁免规则</h2>
      <el-button type="primary" @click="openDialog()">新增规则</el-button>
    </div>

    <el-table :data="records" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="ruleType" label="规则类型" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="row.ruleType === 'SOURCE_CLASS' ? '' : 'warning'">
            {{ ruleTypeLabel(row.ruleType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="pattern" label="匹配值" min-width="260" show-overflow-tooltip />
      <el-table-column prop="reason" label="豁免原因" min-width="200" show-overflow-tooltip />
      <el-table-column label="项目" width="160">
        <template #default="{ row }">{{ row.projectId ? projectName(row.projectId) : '全局' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" link :type="row.status === 1 ? 'warning' : 'success'"
            @click="handleToggle(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="loadData"
      style="margin-top: 16px"
    />

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑规则' : '新增规则'" width="600px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="规则类型" required>
          <el-select v-model="form.ruleType" placeholder="请选择">
            <el-option label="按类名" value="SOURCE_CLASS" />
            <el-option label="按表名" value="TABLE_NAME" />
          </el-select>
        </el-form-item>
        <el-form-item label="匹配值" required>
          <el-input v-model="form.pattern"
            :placeholder="form.ruleType === 'TABLE_NAME'
              ? '表名，如 t_log_operation'
              : '类名，如 DeleteNotAfiDataManagementMapper'" />
        </el-form-item>
        <el-form-item label="所属项目">
          <el-select v-model="form.projectId" placeholder="全局生效" clearable style="width: 100%">
            <el-option
              v-for="p in projects"
              :key="p.id"
              :label="p.projectName"
              :value="p.id"
            />
          </el-select>
          <span style="margin-left: 8px; color: #909399; font-size: 12px">不选择则全局生效</span>
        </el-form-item>
        <el-form-item label="豁免原因">
          <el-input v-model="form.reason" type="textarea" :rows="3"
            placeholder="说明为什么豁免，如：数据清洗脚本，允许全表操作" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handlePreview" :loading="previewing">预览影响范围</el-button>
      </template>
    </el-dialog>

    <!-- 二次确认弹窗：预览匹配结果 -->
    <el-dialog v-model="previewVisible" title="确认豁免范围" width="750px">
      <el-alert
        :title="`该规则将匹配 ${previewData.matchedCount} 条 SQL（共 ${previewData.totalRecords} 条）`"
        :type="previewData.matchedCount > 0 ? 'warning' : 'info'"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
      />

      <div v-if="previewData.matchedCount === 0" style="text-align: center; padding: 20px; color: #909399">
        当前没有已记录的 SQL 匹配此规则，但规则仍会对后续扫描生效。
      </div>

      <el-table v-else :data="previewData.matched" stripe max-height="350" size="small">
        <el-table-column prop="sourceLocation" label="来源" min-width="240" show-overflow-tooltip />
        <el-table-column prop="sqlType" label="类型" width="80" />
        <el-table-column prop="sqlNormalized" label="SQL" min-width="300" show-overflow-tooltip />
      </el-table>

      <template #footer>
        <el-button @click="previewVisible = false">取消</el-button>
        <el-button type="warning" @click="handleConfirmSave">
          确认{{ editingId ? '修改' : '创建' }}豁免规则
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { exemptionApi, projectApi } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatTime } from '../utils/format'

const records = ref<any[]>([])
const page = ref(1)
const total = ref(0)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const previewing = ref(false)
const previewVisible = ref(false)
const previewData = ref<any>({ matchedCount: 0, totalRecords: 0, matched: [] })
const projects = ref<any[]>([])

const form = ref({
  ruleType: 'SOURCE_CLASS',
  pattern: '',
  reason: '',
  projectId: null as number | null
})

function ruleTypeLabel(type: string) {
  return type === 'SOURCE_CLASS' ? '按类名' : type === 'TABLE_NAME' ? '按表名' : type
}

function projectName(id: number) {
  const p = projects.value.find((x: any) => x.id === id)
  return p ? p.projectName : `#${id}`
}

async function loadProjects() {
  try {
    const res: any = await projectApi.list()
    projects.value = res.data ?? []
  } catch {
    // ignore
  }
}

async function loadData() {
  try {
    const res: any = await exemptionApi.page({ page: page.value, size: 20 })
    records.value = res.data?.records ?? []
    total.value = res.data?.total ?? 0
  } catch {
    // ignore
  }
}

function openDialog(row?: any) {
  if (row) {
    editingId.value = row.id
    form.value = {
      ruleType: row.ruleType,
      pattern: row.pattern,
      reason: row.reason || '',
      projectId: row.projectId
    }
  } else {
    editingId.value = null
    form.value = { ruleType: 'SOURCE_CLASS', pattern: '', reason: '', projectId: null }
  }
  dialogVisible.value = true
}

async function handlePreview() {
  if (!form.value.pattern.trim()) {
    ElMessage.warning('请填写匹配值')
    return
  }
  previewing.value = true
  try {
    const res: any = await exemptionApi.preview(
      {
        ruleType: form.value.ruleType,
        pattern: form.value.pattern.trim(),
        projectId: form.value.projectId
      },
      form.value.projectId ? { projectId: form.value.projectId } : undefined
    )
    previewData.value = res.data ?? { matchedCount: 0, totalRecords: 0, matched: [] }
    previewVisible.value = true
  } catch (e: any) {
    ElMessage.error('预览失败: ' + (e.response?.data?.message || e.message))
  } finally {
    previewing.value = false
  }
}

async function handleConfirmSave() {
  try {
    const payload = {
      ruleType: form.value.ruleType,
      pattern: form.value.pattern.trim(),
      reason: form.value.reason,
      projectId: form.value.projectId
    }
    if (editingId.value) {
      await exemptionApi.update(editingId.value, payload)
      ElMessage.success('规则已更新')
    } else {
      await exemptionApi.create(payload)
      ElMessage.success('规则已创建')
    }
    previewVisible.value = false
    dialogVisible.value = false
    loadData()
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function handleToggle(row: any) {
  await exemptionApi.toggle(row.id)
  ElMessage.success(row.status === 1 ? '已禁用' : '已启用')
  loadData()
}

async function handleDelete(row: any) {
  await ElMessageBox.confirm(
    `确定删除规则 #${row.id}（${ruleTypeLabel(row.ruleType)}: ${row.pattern}）？`,
    '删除确认',
    { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
  )
  await exemptionApi.delete(row.id)
  ElMessage.success('已删除')
  loadData()
}

onMounted(() => {
  loadProjects()
  loadData()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
</style>
