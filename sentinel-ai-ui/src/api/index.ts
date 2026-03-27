import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    console.error('API Error:', error)
    return Promise.reject(error)
  }
)

export const dashboardApi = {
  getOverview: () => api.get('/dashboard/overview')
}

export const projectApi = {
  list: () => api.get('/project/list'),
  page: (current: number, size: number) => api.get('/project/page', { params: { current, size } }),
  get: (id: number) => api.get(`/project/${id}`),
  create: (data: any) => api.post('/project', data),
  update: (data: any) => api.put('/project', data),
  delete: (id: number) => api.delete(`/project/${id}`),
  checkRepo: (id: number) => api.get(`/project/check-repo/${id}`),
  checkRepoPath: (path: string) => api.get('/project/check-repo-path', { params: { path } }),
  clone: (id: number) => api.post(`/project/clone/${id}`, null, { timeout: 300000 }),
  clonePathPreview: (projectName: string) => api.get('/project/clone-path-preview', { params: { projectName } }),
}

export const scanApi = {
  trigger: (projectId: number, forceFullScan: boolean = false) =>
    api.post(`/scan/trigger/${projectId}`, null, { params: { forceFullScan } }),
  history: (params: any) => api.get('/scan/history', { params }),
  getBatch: (id: number) => api.get(`/scan/batch/${id}`)
}

export const analysisApi = {
  page: (params: any) => api.get('/analysis/page', { params }),
  pageByBatch: (batchId: number, current: number, size: number) =>
    api.get(`/analysis/batch/${batchId}`, { params: { current, size } }),
  detail: (id: number) => api.get(`/analysis/${id}`),
  handle: (id: number, status: string, note?: string) =>
    api.put(`/analysis/${id}/handle`, null, { params: { status, note } }),
  reanalyze: (sqlRecordId: number) =>
    api.post(`/analysis/reanalyze/${sqlRecordId}`, null, { timeout: 120000 }),
}

export const tableMetaApi = {
  refreshDdl: (limit: number = -1) =>
    api.post('/table-meta/refresh-ddl', null, { params: { limit }, timeout: 600000 }),
  refreshIndexStats: (limit: number = -1) =>
    api.post('/table-meta/refresh-index-stats', null, { params: { limit }, timeout: 600000 }),
  list: () => api.get('/table-meta/list'),
  connectionTest: () => api.get('/table-meta/connection-test'),
}

export const gitApi = {
  config: () => api.get('/git/config'),
  branches: (projectId: number) => api.get(`/git/branches/${projectId}`),
  commits: (projectId: number, branch: string, limit: number = 20) =>
    api.get(`/git/commits/${projectId}`, { params: { branch, limit } }),
  diff: (projectId: number, from: string, to: string) =>
    api.get(`/git/diff/${projectId}`, { params: { from, to } }),
  test: (projectId: number) => api.get(`/git/test/${projectId}`),
}

export const sqlRecordApi = {
  page: (params: any) => api.get('/sql-record/page', { params }),
}

export const exemptionApi = {
  page: (params: any) => api.get('/exemption/page', { params }),
  create: (data: any) => api.post('/exemption', data),
  update: (id: number, data: any) => api.put(`/exemption/${id}`, data),
  delete: (id: number) => api.delete(`/exemption/${id}`),
  toggle: (id: number) => api.put(`/exemption/${id}/toggle`),
  preview: (data: any, params?: any) => api.post('/exemption/preview', data, { params }),
}

export const knowledgeApi = {
  page: (params: any) => api.get('/knowledge/page', { params }),
  list: () => api.get('/knowledge/list'),
  detail: (id: number) => api.get(`/knowledge/${id}`),
  create: (data: any) => api.post('/knowledge', data),
  update: (data: any) => api.put('/knowledge', data),
  delete: (id: number) => api.delete(`/knowledge/${id}`),
  reEmbed: (forceAll: boolean = false) => api.post('/knowledge/re-embed', null, { params: { forceAll } }),
  status: () => api.get('/knowledge/status')
}

export default api
