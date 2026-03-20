<template>
  <div class="projects">
    <div class="page-header">
      <h2>项目管理</h2>
      <el-button type="primary" @click="openCreate">添加项目</el-button>
    </div>

    <el-table :data="projects" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="projectName" label="项目名称" />
      <el-table-column label="代码来源" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.gitRemoteUrl" type="success" size="small">Git 远程</el-tag>
          <el-tag v-else type="info" size="small">本地路径</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gitRepoPath" label="本地仓库路径" show-overflow-tooltip />
      <el-table-column prop="gitProjectPath" label="Git 平台路径" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.gitProjectPath || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="lastScanCommit" label="最后扫描 Commit" width="140" show-overflow-tooltip />
      <el-table-column label="最后扫描时间" width="170">
        <template #default="{ row }">{{ formatTime(row.lastScanTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="400" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="triggerScan(row.id, false)">
            {{ row.lastScanCommit ? '增量扫描' : '全量扫描' }}
          </el-button>
          <el-button v-if="row.lastScanCommit" size="small" @click="triggerScan(row.id, true)">强制全量</el-button>
          <el-button size="small" @click="checkRepoStatus(row)">检查仓库</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="showDialog" :title="isEdit ? '编辑项目' : '添加项目'" width="640" :close-on-click-modal="false">
      <el-form :model="form" label-width="120px">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.projectName" placeholder="如 collection-management" :disabled="isEdit" />
        </el-form-item>

        <el-form-item label="代码来源" required>
          <el-radio-group v-model="form.repoMode" @change="onRepoModeChange">
            <el-radio value="LOCAL">本地路径</el-radio>
            <el-radio value="REMOTE">Git 远程拉取</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- LOCAL mode -->
        <template v-if="form.repoMode === 'LOCAL'">
          <el-form-item label="本地仓库路径" required>
            <el-input v-model="form.gitRepoPath" placeholder="如 E:\cursor\collection-management" />
            <div class="form-tip">指向本地已存在的 Git 仓库目录，扫描时直接读取此路径下的代码</div>
          </el-form-item>
        </template>

        <!-- REMOTE mode -->
        <template v-if="form.repoMode === 'REMOTE'">
          <el-form-item label="Git 远程 URL" required>
            <el-input v-model="form.gitRemoteUrl" placeholder="如 https://git.silvrr.com/risk-backend/collection-management.git" />
            <div class="form-tip">支持 HTTPS 或 SSH 地址，首次保存后将自动 clone 到本地</div>
          </el-form-item>
          <el-form-item label="默认分支">
            <el-input v-model="form.gitBranch" placeholder="master" />
          </el-form-item>
          <el-form-item label="Clone 路径">
            <el-input :model-value="clonePathDisplay" disabled />
            <div class="form-tip">自动计算的 clone 目标路径，保存后将 clone 到此位置</div>
          </el-form-item>
        </template>

        <el-divider content-position="left">表结构配置</el-divider>
        <el-form-item label="表结构来源">
          <el-select v-model="form.tableSchemaSource">
            <el-option label="手动管理" value="MANUAL" />
            <el-option label="数据库连接" value="DB_CONNECT" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.tableSchemaSource === 'DB_CONNECT'" label="数据库地址">
          <el-input v-model="form.dbConnectionUrl" placeholder="jdbc:mysql://..." />
        </el-form-item>

        <el-divider content-position="left">Git 平台 API（可选，用于代码变更页面）</el-divider>
        <div class="form-tip" style="margin-bottom: 12px; padding: 0 30px;">
          Git 平台 Token 在配置文件中统一设置，此处只需填写项目在 Git 平台上的路径，用于「代码变更」页面查看分支和提交。
        </div>
        <el-form-item label="Git 项目路径">
          <el-input v-model="form.gitProjectPath" placeholder="如 risk-backend/collection-management" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">{{ isEdit ? '保存' : '确定' }}</el-button>
      </template>
    </el-dialog>

    <!-- Repo Status Dialog -->
    <el-dialog v-model="showRepoStatus" title="仓库状态" width="480">
      <div v-if="repoStatus" class="repo-status">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="项目">{{ repoStatusProject }}</el-descriptions-item>
          <el-descriptions-item label="本地路径">{{ repoStatus.absolutePath || '-' }}</el-descriptions-item>
          <el-descriptions-item label="目录存在">
            <el-tag :type="repoStatus.exists ? 'success' : 'danger'" size="small">{{ repoStatus.exists ? '是' : '否' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="是 Git 仓库">
            <el-tag :type="repoStatus.isGitRepo ? 'success' : 'warning'" size="small">{{ repoStatus.isGitRepo ? '是' : '否' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="文件/目录数">{{ repoStatus.fileCount }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="repoStatus.exists && !repoStatus.isGitRepo" class="status-warning">
          目录存在但不是 Git 仓库（无 .git 目录），可能路径不正确。
        </div>
        <div v-if="!repoStatus.exists" class="status-warning">
          目录不存在。如果是远程模式，请点击「Clone/Pull」按钮拉取代码。
        </div>
        <div v-if="repoStatus.exists && repoStatus.isGitRepo && repoStatus.fileCount > 0" class="status-ok">
          仓库状态正常，可以进行扫描。
        </div>
      </div>
      <template #footer>
        <el-button v-if="repoStatusProjectId && repoStatusHasRemote" type="primary" @click="doClone" :loading="cloning">
          Clone / Pull
        </el-button>
        <el-button @click="showRepoStatus = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, scanApi } from '../api'
import { formatTime } from '../utils/format'

const projects = ref<any[]>([])
const showDialog = ref(false)
const isEdit = ref(false)
const saving = ref(false)

const showRepoStatus = ref(false)
const repoStatus = ref<any>(null)
const repoStatusProject = ref('')
const repoStatusProjectId = ref<number | null>(null)
const repoStatusHasRemote = ref(false)
const cloning = ref(false)

const clonePathDisplay = ref('')

const defaultForm = () => ({
  id: null as number | null,
  projectName: '',
  repoMode: 'LOCAL' as 'LOCAL' | 'REMOTE',
  gitRepoPath: '',
  gitRemoteUrl: '',
  gitBranch: 'master',
  tableSchemaSource: 'MANUAL',
  dbConnectionUrl: '',
  gitProjectPath: '',
})

const form = ref(defaultForm())

watch(() => form.value.projectName, async (name) => {
  if (form.value.repoMode === 'REMOTE' && name && name.trim()) {
    try {
      const res: any = await projectApi.clonePathPreview(name.trim())
      clonePathDisplay.value = res.code === 200 ? res.data : ''
    } catch { clonePathDisplay.value = '' }
  }
})

const onRepoModeChange = () => {
  if (form.value.repoMode === 'LOCAL') {
    form.value.gitRemoteUrl = ''
    clonePathDisplay.value = ''
  } else {
    form.value.gitRepoPath = ''
    if (form.value.projectName) {
      projectApi.clonePathPreview(form.value.projectName).then((res: any) => {
        clonePathDisplay.value = res.code === 200 ? res.data : ''
      }).catch(() => {})
    }
  }
}

const loadProjects = async () => {
  try {
    const res: any = await projectApi.list()
    projects.value = res.data ?? []
  } catch { /* ignore */ }
}

const openCreate = () => {
  isEdit.value = false
  form.value = defaultForm()
  clonePathDisplay.value = ''
  showDialog.value = true
}

const openEdit = (row: any) => {
  isEdit.value = true
  const hasRemote = row.gitRemoteUrl && row.gitRemoteUrl.trim()
  form.value = {
    id: row.id,
    projectName: row.projectName ?? '',
    repoMode: hasRemote ? 'REMOTE' : 'LOCAL',
    gitRepoPath: row.gitRepoPath ?? '',
    gitRemoteUrl: row.gitRemoteUrl ?? '',
    gitBranch: row.gitBranch ?? 'master',
    tableSchemaSource: row.tableSchemaSource ?? 'MANUAL',
    dbConnectionUrl: row.dbConnectionUrl ?? '',
    gitProjectPath: row.gitProjectPath ?? '',
  }
  if (hasRemote) {
    clonePathDisplay.value = row.gitRepoPath ?? ''
  } else {
    clonePathDisplay.value = ''
  }
  showDialog.value = true
}

const handleSave = async () => {
  if (!form.value.projectName.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  if (form.value.repoMode === 'LOCAL' && !form.value.gitRepoPath.trim()) {
    ElMessage.warning('请填写本地仓库路径')
    return
  }
  if (form.value.repoMode === 'REMOTE' && !form.value.gitRemoteUrl.trim()) {
    ElMessage.warning('请填写 Git 远程 URL')
    return
  }

  if (form.value.repoMode === 'REMOTE' && !isEdit.value) {
    try {
      await ElMessageBox.confirm(
        `将 clone 远程仓库到本地路径：\n${clonePathDisplay.value}\n\n确认保存并 clone？`,
        'Clone 确认',
        { type: 'info', confirmButtonText: '确认', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  saving.value = true
  try {
    const payload = { ...form.value } as any
    delete payload.repoMode
    if (form.value.repoMode === 'LOCAL') {
      payload.gitRemoteUrl = ''
    }

    if (isEdit.value) {
      await projectApi.update(payload)
      ElMessage.success('项目更新成功')
    } else {
      await projectApi.create(payload)
      ElMessage.success('项目添加成功')
    }

    showDialog.value = false

    await loadProjects()

    if (form.value.repoMode === 'REMOTE') {
      const savedProject = projects.value.find(p => p.projectName === form.value.projectName)
      if (savedProject) {
        ElMessage.info('正在 clone 远程仓库...')
        try {
          const cloneRes: any = await projectApi.clone(savedProject.id)
          if (cloneRes.code === 200) {
            ElMessage.success(`Clone 成功！路径: ${cloneRes.data.localPath}`)
          } else {
            ElMessage.error(cloneRes.message || 'Clone 失败')
          }
        } catch {
          ElMessage.error('Clone 失败，请稍后在仓库状态中重试')
        }
      }
    }

    form.value = defaultForm()
  } catch {
    ElMessage.error(isEdit.value ? '更新失败' : '添加失败')
  } finally {
    saving.value = false
  }
}

const checkRepoStatus = async (row: any) => {
  repoStatusProject.value = row.projectName
  repoStatusProjectId.value = row.id
  repoStatusHasRemote.value = !!(row.gitRemoteUrl && row.gitRemoteUrl.trim())
  repoStatus.value = null
  showRepoStatus.value = true
  try {
    const res: any = await projectApi.checkRepo(row.id)
    if (res.code === 200) {
      repoStatus.value = res.data
    } else {
      ElMessage.error(res.message || '检查失败')
    }
  } catch {
    ElMessage.error('检查仓库状态失败')
  }
}

const doClone = async () => {
  if (!repoStatusProjectId.value) return
  cloning.value = true
  try {
    const res: any = await projectApi.clone(repoStatusProjectId.value)
    if (res.code === 200) {
      ElMessage.success('Clone/Pull 成功')
      repoStatus.value = res.data.status
      await loadProjects()
    } else {
      ElMessage.error(res.message || 'Clone 失败')
    }
  } catch {
    ElMessage.error('Clone 失败')
  } finally {
    cloning.value = false
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
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}
.repo-status {
  padding: 8px 0;
}
.status-warning {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fdf6ec;
  color: #e6a23c;
  border-radius: 4px;
  font-size: 13px;
}
.status-ok {
  margin-top: 12px;
  padding: 8px 12px;
  background: #f0f9eb;
  color: #67c23a;
  border-radius: 4px;
  font-size: 13px;
}
</style>
