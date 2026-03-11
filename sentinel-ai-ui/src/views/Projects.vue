<template>
  <div class="projects">
    <div class="page-header">
      <h2>项目管理</h2>
      <el-button type="primary" @click="showDialog = true">添加项目</el-button>
    </div>

    <el-table :data="projects" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="projectName" label="项目名称" />
      <el-table-column prop="gitRepoPath" label="Git 仓库路径" show-overflow-tooltip />
      <el-table-column prop="lastScanCommit" label="最后扫描 Commit" width="180" show-overflow-tooltip />
      <el-table-column prop="lastScanTime" label="最后扫描时间" width="180" />
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="triggerScan(row.id, false)">
            {{ row.lastScanCommit ? '增量扫描' : '全量扫描' }}
          </el-button>
          <el-button v-if="row.lastScanCommit" size="small" @click="triggerScan(row.id, true)">强制全量</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showDialog" title="添加项目" width="500">
      <el-form :model="form" label-width="100px">
        <el-form-item label="项目名称">
          <el-input v-model="form.projectName" placeholder="如 collection-management" />
        </el-form-item>
        <el-form-item label="Git 路径">
          <el-input v-model="form.gitRepoPath" placeholder="如 e:\cursor\collection-management" />
        </el-form-item>
        <el-form-item label="表结构来源">
          <el-select v-model="form.tableSchemaSource">
            <el-option label="手动管理" value="MANUAL" />
            <el-option label="数据库连接" value="DB_CONNECT" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.tableSchemaSource === 'DB_CONNECT'" label="数据库地址">
          <el-input v-model="form.dbConnectionUrl" placeholder="jdbc:mysql://..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, scanApi } from '../api'

const projects = ref<any[]>([])
const showDialog = ref(false)
const form = ref({
  projectName: '',
  gitRepoPath: '',
  tableSchemaSource: 'MANUAL',
  dbConnectionUrl: ''
})

const loadProjects = async () => {
  try {
    const res: any = await projectApi.list()
    projects.value = res.data ?? []
  } catch {
    // handle error
  }
}

const handleCreate = async () => {
  try {
    await projectApi.create(form.value)
    ElMessage.success('项目添加成功')
    showDialog.value = false
    form.value = { projectName: '', gitRepoPath: '', tableSchemaSource: 'MANUAL', dbConnectionUrl: '' }
    await loadProjects()
  } catch {
    ElMessage.error('添加失败')
  }
}

const triggerScan = async (projectId: number, forceFullScan: boolean = false) => {
  try {
    await scanApi.trigger(projectId, forceFullScan)
    ElMessage.success(forceFullScan ? '全量扫描已触发' : '扫描已触发')
  } catch {
    ElMessage.error('触发扫描失败')
  }
}

const handleDelete = async (id: number) => {
  await ElMessageBox.confirm('确定删除该项目？', '提示', { type: 'warning' })
  try {
    await projectApi.delete(id)
    ElMessage.success('删除成功')
    await loadProjects()
  } catch {
    ElMessage.error('删除失败')
  }
}

onMounted(loadProjects)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
