/* ===== 用户 store ===== */
import { defineStore } from 'pinia'
import { authApi } from '@/api'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('library_token') || '',
    user: JSON.parse(localStorage.getItem('library_user') || 'null'),
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    userType: (state) => state.user?.userType,
    isStudent: (state) => state.user?.userType === 2,
    isTeacher: (state) => state.user?.userType === 1,
    isAdmin: (state) => state.user?.userType === 3,
  },
  actions: {
    async login({ username, password }) {
      const data = await authApi.login({ username, password })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('library_token', data.token)
      localStorage.setItem('library_user', JSON.stringify(data.user))
      return data
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('library_token')
      localStorage.removeItem('library_user')
    },
  },
})
