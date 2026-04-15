<template>
  <div class="dashboard">
    <h2>仪表盘</h2>
    <el-row :gutter="20" class="stat-cards">
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>扫描项目数</template>
          <div class="stat-number">{{ overview.projectCount ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>SQL 总数</template>
          <div class="stat-number">{{ overview.totalSqlCount ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>
            <span>风险 SQL</span>
            <el-tooltip content="包含 P0(紧急)、P1(高危)、P2(中危) 等级的 SQL" placement="top">
              <el-icon style="margin-left: 4px; vertical-align: middle; cursor: help;"><QuestionFilled /></el-icon>
            </el-tooltip>
          </template>
          <div class="stat-number danger">{{ overview.riskSqlCount ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>
            <span>待处理 (P0/P1)</span>
            <el-tooltip content="P0: 紧急-必定慢SQL | P1: 高危-大概率慢SQL，需优先处理" placement="top">
              <el-icon style="margin-left: 4px; vertical-align: middle; cursor: help;"><QuestionFilled /></el-icon>
            </el-tooltip>
          </template>
          <div class="stat-number warning">{{ overview.pendingCount ?? '-' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="welcome-card" shadow="never">
      <h3>Welcome to Sentinel AI</h3>
      <p>SQL 性能守卫 —— 扫描、分析、优化你的 SQL，由 AI 驱动。</p>
      <el-steps :active="0" align-center>
        <el-step title="配置项目" description="添加 Git 仓库路径" />
        <el-step title="触发扫描" description="全量或增量扫描 SQL" />
        <el-step title="AI 分析" description="自动识别慢 SQL 风险" />
        <el-step title="查看报告" description="优化建议和索引推荐" />
      </el-steps>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { dashboardApi } from '../api'

const overview = ref<Record<string, any>>({})

onMounted(async () => {
  try {
    const res: any = await dashboardApi.getOverview()
    overview.value = res.data ?? {}
  } catch {
    // dev mode - backend may not be running
  }
})
</script>

<style scoped>
.dashboard {
  padding: 10px;
}
.stat-cards {
  margin-bottom: 24px;
}
.stat-number {
  font-size: 36px;
  font-weight: 700;
  text-align: center;
  color: #409eff;
}
.stat-number.danger {
  color: #f56c6c;
}
.stat-number.warning {
  color: #e6a23c;
}
.welcome-card {
  margin-top: 20px;
}
.welcome-card h3 {
  margin-top: 0;
}
</style>
