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
  delete: (id: number) => api.delete(`/project/${id}`)
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
    api.put(`/analysis/${id}/handle`, null, { params: { status, note } })
}

export default api
