import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'Dashboard',
      component: () => import('../views/Dashboard.vue')
    },
    {
      path: '/projects',
      name: 'Projects',
      component: () => import('../views/Projects.vue')
    },
    {
      path: '/scans',
      name: 'Scans',
      component: () => import('../views/Scans.vue')
    },
    {
      path: '/sql-records',
      name: 'SqlRecords',
      component: () => import('../views/SqlRecords.vue')
    },
    {
      path: '/analysis',
      name: 'Analysis',
      component: () => import('../views/Analysis.vue')
    },
    {
      path: '/table-meta',
      name: 'TableMeta',
      component: () => import('../views/TableMeta.vue')
    },
    {
      path: '/git',
      name: 'GitIntegration',
      component: () => import('../views/GitIntegration.vue')
    },
    {
      path: '/knowledge',
      name: 'Knowledge',
      component: () => import('../views/Knowledge.vue')
    },
    {
      path: '/exemptions',
      name: 'Exemptions',
      component: () => import('../views/Exemptions.vue')
    },
    {
      path: '/prompts',
      name: 'Prompts',
      component: () => import('../views/Prompts.vue')
    }
  ]
})

export default router
