<script setup lang="ts">
import { reactive, shallowRef, useTemplateRef } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { changePasswordApi, resolveApiError } from '../../../api/auth'

/**
 * 强制改密对话框：首次登录 / 重置密码后 mustChangePassword=true 时弹出。
 * 不可点遮罩关闭；改密成功后旧令牌全部失效，由父组件清登录态回登录页。
 */
const props = defineProps<{
  oldPassword: string
}>()

const emit = defineEmits<{
  success: []
}>()

const visible = defineModel<boolean>('visible', { required: true })

const formRef = useTemplateRef<FormInstance>('formRef')
const submitting = shallowRef(false)

const form = reactive({
  oldPassword: props.oldPassword,
  newPassword: '',
  confirmPassword: '',
})

const validateNewPassword = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (!value) {
    callback(new Error('请输入新密码'))
  } else if (!/^(?=.*[A-Za-z])(?=.*\d).{8,64}$/.test(value)) {
    callback(new Error('新密码须 8-64 位，且同时包含字母和数字'))
  } else if (value === form.oldPassword) {
    callback(new Error('新密码不能与旧密码相同'))
  } else {
    callback()
  }
}

const validateConfirm = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (!value) {
    callback(new Error('请再次输入新密码'))
  } else if (value !== form.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [{ validator: validateNewPassword, trigger: 'blur' }],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await changePasswordApi({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    visible.value = false
    emit('success')
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="修改初始密码"
    width="420px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    align-center
  >
    <p class="dialog-tip">首次登录须修改初始密码后方可继续使用</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" placeholder="请输入旧密码" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" placeholder="8-64 位，须含字母和数字" show-password />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入新密码" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-tip {
  font-size: 12px;
  color: #5E6D82;
  margin: 0 0 16px;
}
</style>
