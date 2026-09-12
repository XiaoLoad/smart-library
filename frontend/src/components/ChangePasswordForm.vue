<template>
  <el-form
    ref="formRef"
    :model="form"
    :rules="rules"
    label-width="92px"
    class="change-password-form"
    @submit.prevent
  >
    <el-form-item label="原密码" prop="oldPassword">
      <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
    </el-form-item>
    <el-form-item label="新密码" prop="newPassword">
      <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 6 位" />
    </el-form-item>
    <el-form-item label="确认密码" prop="confirmPassword">
      <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="loading" @click="submit">确认修改</el-button>
      <el-button @click="reset">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { systemApi } from '@/api'

/**
 * 修改本人登录密码。
 * 后端 /system/change-password 按 token 里的本人 id 处理并校验原密码，
 * 因此管理员与读者共用同一套逻辑，差别只在放在哪个页面。
 */
const formRef = ref(null)
const loading = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

const reset = () => {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

const submit = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const res = await systemApi.changePassword({
        oldPassword: form.oldPassword,
        newPassword: form.newPassword,
      })
      ElMessage.success(res.message || '密码修改成功')
      reset()
    } catch {
      // 错误已由拦截器提示
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.change-password-form {
  max-width: 460px;
}
</style>
