<template>
  <div class="prompts">
    <div class="page-header">
      <h2>Prompt 管理</h2>
      <el-button type="primary" @click="openDialog()">新增模板</el-button>
    </div>

    <el-alert type="info" :closable="false" style="margin-bottom: 16px">
      修改系统提示词（SQL_ANALYSIS_SYSTEM）后，下次 AI 分析时立即生效，无需重启服务。
      用户提示词模板中的 <code>%s</code> 为占位符，请勿删除。
    </el-alert>

    <el-table :data="templates" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="templateKey" label="模板 Key" width="220">
        <template #default="{ row }">
          <el-tag size="small">{{ row.templateKey }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="templateName" label="名称" width="200" />
      <el-table-column prop="content" label="内容预览" min-width="300" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            @change="handleToggle(row)"
            inline-prompt
            active-text="启用"
            inactive-text="禁用"
          />
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-popconfirm title="确认删除该模板？" @confirm="handleDelete(row)">
            <template #reference>
              <el-button size="small" link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑模板' : '新增模板'" width="70%" top="5vh">
      <el-form :model="form" label-width="100px">
        <el-form-item label="模板 Key" required>
          <el-input v-model="form.templateKey" :disabled="isEdit" placeholder="如 SQL_ANALYSIS_SYSTEM" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.templateName" placeholder="模板显示名称" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="模板用途说明" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="18"
            placeholder="Prompt 内容"
            style="font-family: 'Cascadia Code', 'Consolas', monospace; font-size: 13px"
          />
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
import { promptApi } from '../api'
import { ElMessage } from 'element-plus'

interface PromptTemplate {
  id?: number
  templateKey: string
  templateName: string
  content: string
  description?: string
  status?: number
  createTime?: string
  updateTime?: string
}

const templates = ref<PromptTemplate[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const form = ref<PromptTemplate>({
  templateKey: '',
  templateName: '',
  content: '',
  description: ''
})

const loadData = async () => {
  try {
    const res: any = await promptApi.list()
    templates.value = res.data || []
  } catch (e) {
    ElMessage.error('加载模板列表失败')
  }
}

const openDialog = (row?: PromptTemplate) => {
  if (row) {
    isEdit.value = true
    form.value = { ...row }
  } else {
    isEdit.value = false
    form.value = { templateKey: '', templateName: '', content: '', description: '' }
  }
  dialogVisible.value = true
}

const handleSave = async () => {
  if (!form.value.templateKey || !form.value.templateName || !form.value.content) {
    ElMessage.warning('请填写必填项')
    return
  }
  saving.value = true
  try {
    if (isEdit.value && form.value.id) {
      await promptApi.update(form.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await promptApi.create(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await loadData()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const handleToggle = async (row: PromptTemplate) => {
  try {
    await promptApi.toggle(row.id!)
    await loadData()
    ElMessage.success('状态更新成功')
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const handleDelete = async (row: PromptTemplate) => {
  try {
    await promptApi.delete(row.id!)
    ElMessage.success('删除成功')
    await loadData()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const formatTime = (t: string) => {
  if (!t) return ''
  return t.replace('T', ' ').substring(0, 19)
}

onMounted(loadData)
</script>

<style scoped>
.prompts {
  padding: 0;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
}
</style>
