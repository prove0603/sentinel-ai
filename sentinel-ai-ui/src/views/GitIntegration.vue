<template>
  <div class="git-integration">
    <div class="page-header">
      <h2>代码变更</h2>
      <el-tag v-if="gitEnabled" type="success" size="small">{{ gitPlatform }} 已连接</el-tag>
      <el-tag v-else type="danger" size="small">Git 平台未配置</el-tag>
    </div>

    <!-- Not configured hint -->
    <el-alert v-if="!gitEnabled" title="Git 平台未配置" type="warning" show-icon :closable="false" style="margin-bottom: 16px">
      请在配置文件 application-dev.yml 中设置 sentinel.git.platform、api-url 和 access-token。
    </el-alert>

    <!-- Toolbar -->
    <div class="toolbar">
      <el-select v-model="selectedProjectId" placeholder="选择项目" @change="onProjectChange" style="width: 280px" clearable>
        <el-option v-for="p in configuredProjects" :key="p.id" :value="p.id" :label="p.projectName">
          <span>{{ p.projectName }}</span>
          <span style="margin-left: 8px; color: #909399; font-size: 12px">{{ p.gitProjectPath }}</span>
        </el-option>
      </el-select>

      <el-select
        v-model="selectedBranch"
        placeholder="选择分支"
        @change="onBranchChange"
        style="width: 220px; margin-left: 12px"
        :disabled="!selectedProjectId || loadingBranches"
        :loading="loadingBranches"
        filterable
      >
        <el-option v-for="b in branches" :key="b.name" :value="b.name" :label="b.name" />
      </el-select>

      <el-button style="margin-left: 12px" @click="testConnection" :loading="testing" :disabled="!selectedProjectId || !gitEnabled">
        测试连接
      </el-button>
    </div>

    <!-- No projects hint -->
    <el-empty v-if="configuredProjects.length === 0 && gitEnabled" description="暂无配置 Git 项目路径的项目，请先在项目管理中编辑项目并填写 Git 项目路径" />

    <!-- Commits Table -->
    <template v-if="selectedProjectId && selectedBranch">
      <div class="section-header">
        <h3>最近提交</h3>
        <el-button size="small" @click="loadCommits" :loading="loadingCommits">刷新</el-button>
      </div>

      <el-table :data="commits" stripe v-loading="loadingCommits" @row-click="onCommitClick" highlight-current-row style="cursor: pointer">
        <el-table-column label="Commit" width="100">
          <template #default="{ row }">
            <code>{{ row.shortId }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="提交信息" show-overflow-tooltip />
        <el-table-column prop="author" label="作者" width="140" />
        <el-table-column label="时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.parentIds && row.parentIds.length > 0" size="small" link type="primary" @click.stop="viewDiff(row.parentIds[0], row.id)">
              查看变更
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- Diff Drawer -->
    <el-drawer v-model="showDiff" title="变更文件" size="60%" direction="rtl">
      <div v-loading="loadingDiff">
        <div v-if="diffInfo" class="diff-summary">
          <span><code>{{ diffInfo.fromCommit?.substring(0, 8) }}</code></span>
          <el-icon style="margin: 0 8px"><Right /></el-icon>
          <span><code>{{ diffInfo.toCommit?.substring(0, 8) }}</code></span>
          <el-tag style="margin-left: 12px" size="small">{{ diffInfo.files?.length ?? 0 }} 个文件变更</el-tag>
        </div>

        <el-table :data="diffInfo?.files ?? []" stripe style="margin-top: 12px">
          <el-table-column label="文件路径" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="'file-' + row.status">{{ row.path }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="增/删" width="120">
            <template #default="{ row }">
              <span class="additions">+{{ row.additions }}</span>
              <span class="deletions" style="margin-left: 8px">-{{ row.deletions }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Right } from '@element-plus/icons-vue'
import { projectApi, gitApi } from '../api'
import { formatTime } from '../utils/format'

interface Project {
  id: number
  projectName: string
  gitProjectPath?: string
  gitBranch?: string
}
interface Branch { name: string }
interface Commit {
  id: string
  shortId: string
  title: string
  author: string
  createdAt: string
  parentIds: string[]
}
interface DiffFile {
  path: string
  status: string
  additions: number
  deletions: number
}
interface DiffInfo {
  fromCommit: string
  toCommit: string
  files: DiffFile[]
}

const allProjects = ref<Project[]>([])
const configuredProjects = computed(() => allProjects.value.filter(p => p.gitProjectPath && p.gitProjectPath.trim()))

const gitEnabled = ref(false)
const gitPlatform = ref('NONE')

const selectedProjectId = ref<number | null>(null)
const selectedBranch = ref('')

const branches = ref<Branch[]>([])
const commits = ref<Commit[]>([])
const diffInfo = ref<DiffInfo | null>(null)

const loadingBranches = ref(false)
const loadingCommits = ref(false)
const loadingDiff = ref(false)
const testing = ref(false)
const showDiff = ref(false)

const loadGitConfig = async () => {
  try {
    const res: any = await gitApi.config()
    if (res.code === 200) {
      gitEnabled.value = res.data?.enabled ?? false
      gitPlatform.value = res.data?.platform ?? 'NONE'
    }
  } catch {
    // ignore
  }
}

const loadProjects = async () => {
  try {
    const res: any = await projectApi.list()
    allProjects.value = res.data ?? []
  } catch {
    // ignore
  }
}

const onProjectChange = async (projectId: number | null) => {
  branches.value = []
  commits.value = []
  selectedBranch.value = ''
  diffInfo.value = null
  if (!projectId) return
  await loadBranches(projectId)
}

const loadBranches = async (projectId: number) => {
  loadingBranches.value = true
  try {
    const res: any = await gitApi.branches(projectId)
    if (res.code === 200) {
      branches.value = res.data ?? []
      const project = allProjects.value.find(p => p.id === projectId)
      const defaultBranch = project?.gitBranch || 'master'
      const matchBranch = branches.value.find(b => b.name === defaultBranch) || branches.value[0]
      if (matchBranch) {
        selectedBranch.value = matchBranch.name
        await loadCommits()
      }
    } else {
      ElMessage.error(res.message || '获取分支失败')
    }
  } catch {
    ElMessage.error('获取分支列表失败')
  } finally {
    loadingBranches.value = false
  }
}

const onBranchChange = () => {
  commits.value = []
  diffInfo.value = null
  if (selectedBranch.value) loadCommits()
}

const loadCommits = async () => {
  if (!selectedProjectId.value || !selectedBranch.value) return
  loadingCommits.value = true
  try {
    const res: any = await gitApi.commits(selectedProjectId.value, selectedBranch.value, 30)
    if (res.code === 200) {
      commits.value = res.data ?? []
    } else {
      ElMessage.error(res.message || '获取提交历史失败')
    }
  } catch {
    ElMessage.error('获取提交历史失败')
  } finally {
    loadingCommits.value = false
  }
}

const onCommitClick = (row: Commit) => {
  if (row.parentIds && row.parentIds.length > 0) {
    viewDiff(row.parentIds[0], row.id)
  }
}

const viewDiff = async (from: string, to: string) => {
  if (!selectedProjectId.value) return
  showDiff.value = true
  loadingDiff.value = true
  diffInfo.value = null
  try {
    const res: any = await gitApi.diff(selectedProjectId.value, from, to)
    if (res.code === 200) {
      diffInfo.value = res.data
    } else {
      ElMessage.error(res.message || '获取变更详情失败')
    }
  } catch {
    ElMessage.error('获取变更详情失败')
  } finally {
    loadingDiff.value = false
  }
}

const testConnection = async () => {
  if (!selectedProjectId.value) return
  testing.value = true
  try {
    const res: any = await gitApi.test(selectedProjectId.value)
    if (res.code === 200) {
      const data = res.data
      ElMessage.success(`连接成功！平台: ${data.platform}，分支数: ${data.branchCount}`)
    } else {
      ElMessage.error(res.message || '连接测试失败')
    }
  } catch {
    ElMessage.error('连接测试失败')
  } finally {
    testing.value = false
  }
}


const statusTagType = (status: string) => {
  switch (status) {
    case 'added': return 'success'
    case 'deleted': return 'danger'
    case 'renamed': return 'warning'
    default: return ''
  }
}

const statusLabel = (status: string) => {
  switch (status) {
    case 'added': return '新增'
    case 'deleted': return '删除'
    case 'modified': return '修改'
    case 'renamed': return '重命名'
    default: return status
  }
}

onMounted(async () => {
  await Promise.all([loadGitConfig(), loadProjects()])
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.section-header h3 {
  margin: 0;
}
.diff-summary {
  display: flex;
  align-items: center;
  padding: 8px 0;
}
.diff-summary code {
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}
.file-added { color: #67c23a; }
.file-deleted { color: #f56c6c; text-decoration: line-through; }
.file-renamed { color: #e6a23c; }
.additions { color: #67c23a; font-weight: 600; }
.deletions { color: #f56c6c; font-weight: 600; }
</style>
