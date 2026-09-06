<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef, useTemplateRef } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createUserApi,
  fetchDepartmentsApi,
  fetchRolesApi,
  fetchUsersApi,
  resetUserPasswordApi,
  resolveApiError,
  updateUserApi,
  updateUserRoleApi,
  updateUserStatusApi,
  type DepartmentItem,
  type RoleItem,
  type UserItem,
} from '../../../api/auth'
import { formatDateTime } from '../../../utils/format'

/**
 * 用户标签页：用户列表（分页 10/20/50）+ 新增 / 编辑 / 停用启用 / 角色指派 / 重置密码。
 * 接口：auth-user-service 3.2（权限点 manageUser）。
 */

// ---------- 列表 ----------
const loading = shallowRef(false)
const userList = ref<UserItem[]>([])
const total = shallowRef(0)
const page = shallowRef(1)
const size = shallowRef(20)
const keyword = shallowRef('')

const departments = ref<DepartmentItem[]>([])
const roles = ref<RoleItem[]>([])

const roleNameMap = computed(() => {
  const map: Record<string, string> = {}
  for (const r of roles.value) map[r.roleKey] = r.name
  return map
})

async function loadUsers() {
  loading.value = true
  try {
    const data = await fetchUsersApi({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    userList.value = data.list
    total.value = data.total
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadUsers()
}

onMounted(async () => {
  loadUsers()
  try {
    const [deptData, roleData] = await Promise.all([fetchDepartmentsApi(), fetchRolesApi()])
    departments.value = deptData
    roles.value = roleData.roles
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
})

// ---------- 新增用户 ----------
const createVisible = shallowRef(false)
const createSubmitting = shallowRef(false)
const createFormRef = useTemplateRef<FormInstance>('createFormRef')
const createForm = reactive({
  name: '',
  account: '',
  departmentId: undefined as number | undefined,
  roleId: undefined as number | undefined,
  email: '',
})

const createRules: FormRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  account: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^\w+$/, message: '账号仅支持字母、数字、下划线', trigger: 'blur' },
  ],
  departmentId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  roleId: [{ required: true, message: '请选择角色', trigger: 'change' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
}

function openCreate() {
  createForm.name = ''
  createForm.account = ''
  createForm.departmentId = undefined
  createForm.roleId = undefined
  createForm.email = ''
  createVisible.value = true
}

async function submitCreate() {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  createSubmitting.value = true
  try {
    const data = await createUserApi({
      name: createForm.name.trim(),
      account: createForm.account.trim(),
      departmentId: createForm.departmentId!,
      roleId: createForm.roleId!,
      email: createForm.email.trim(),
    })
    createVisible.value = false
    await ElMessageBox.alert(
      `用户「${createForm.name.trim()}」已创建，初始密码：${data.initialPassword}。初始密码仅显示一次，请立即转告本人，首次登录须修改。`,
      '用户已创建',
      { confirmButtonText: '知道了', type: 'success' },
    )
    loadUsers()
  } catch (error) {
    const { code, message } = resolveApiError(error)
    ElMessage.error(code === 3007 ? '该账号已存在，请更换账号' : message)
  } finally {
    createSubmitting.value = false
  }
}

// ---------- 编辑（部门 / 邮箱） ----------
const editVisible = shallowRef(false)
const editSubmitting = shallowRef(false)
const editFormRef = useTemplateRef<FormInstance>('editFormRef')
const editTarget = shallowRef<UserItem | null>(null)
const editForm = reactive({
  departmentId: undefined as number | undefined,
  email: '',
})

const editRules: FormRules = {
  departmentId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
}

function openEdit(row: UserItem) {
  editTarget.value = row
  editForm.departmentId = row.departmentId
  editForm.email = row.email
  editVisible.value = true
}

async function submitEdit() {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid || !editTarget.value) return
  editSubmitting.value = true
  try {
    await updateUserApi(editTarget.value.id, {
      departmentId: editForm.departmentId!,
      email: editForm.email.trim(),
    })
    ElMessage.success('已保存')
    editVisible.value = false
    loadUsers()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    editSubmitting.value = false
  }
}

// ---------- 停用 / 启用 ----------
async function toggleStatus(row: UserItem) {
  const disabling = row.status === 'active'
  if (disabling) {
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」将无法登录系统，已签发的令牌立即失效。`,
        '停用用户',
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return // 用户取消
    }
  }
  try {
    await updateUserStatusApi(row.id, disabling ? 'disabled' : 'active')
    ElMessage.success(disabling ? '已停用' : '已启用')
    loadUsers()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
}

// ---------- 角色指派 ----------
const roleVisible = shallowRef(false)
const roleSubmitting = shallowRef(false)
const roleTarget = shallowRef<UserItem | null>(null)
const roleSelected = shallowRef<number | undefined>(undefined)

function openRoleAssign(row: UserItem) {
  roleTarget.value = row
  roleSelected.value = row.roleId
  roleVisible.value = true
}

async function submitRoleAssign() {
  if (!roleTarget.value || !roleSelected.value) return
  roleSubmitting.value = true
  try {
    await updateUserRoleApi(roleTarget.value.id, roleSelected.value)
    ElMessage.success('已指派角色')
    roleVisible.value = false
    loadUsers()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    roleSubmitting.value = false
  }
}

// ---------- 重置密码 ----------
async function handleResetPassword(row: UserItem) {
  try {
    await ElMessageBox.confirm(
      `重置后「${row.name}」的原密码立即失效，本人首次登录须修改新密码。`,
      '重置密码',
      { confirmButtonText: '重置密码', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const data = await resetUserPasswordApi(row.id)
    await ElMessageBox.alert(
      `「${row.name}」的新密码：${data.newPassword}。新密码仅显示一次，请立即转告本人。`,
      '密码已重置',
      { confirmButtonText: '知道了', type: 'success' },
    )
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  }
}
</script>

<template>
  <div class="user-tab">
    <!-- 筛选栏 -->
    <div class="toolbar">
      <el-input
        v-model="keyword"
        class="keyword-input"
        placeholder="搜索姓名 / 账号"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-button @click="handleSearch">搜索</el-button>
      <el-button v-perm="'manageUser'" type="primary" class="create-btn" @click="openCreate">
        新增用户
      </el-button>
    </div>

    <el-table v-loading="loading" v-col-resizable :data="userList">
      <el-table-column prop="name" label="姓名" width="110" show-overflow-tooltip />
      <el-table-column prop="account" label="账号" width="130" show-overflow-tooltip />
      <el-table-column prop="departmentName" label="部门" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.departmentName || '—' }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="110">
        <template #default="{ row }">{{ roleNameMap[row.roleKey] ?? row.roleKey }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span v-if="row.status === 'active'">
            <span class="tf-dot" style="background-color: #2F9E6E"></span>启用
          </span>
          <span v-else>
            <span class="tf-dot" style="background-color: #C3CBD4"></span>停用
          </span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">
          <span class="tf-num">{{ formatDateTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <div class="row-ops">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'active'" link type="danger" @click="toggleStatus(row)">停用</el-button>
            <el-button v-else link type="primary" @click="toggleStatus(row)">启用</el-button>
            <el-button link type="primary" @click="openRoleAssign(row)">角色指派</el-button>
            <el-button link type="primary" @click="handleResetPassword(row)">重置密码</el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-tip">还没有用户，点击右上角「新增用户」创建第一个。</div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="loadUsers"
        @size-change="handleSearch"
      />
    </div>

    <!-- 新增用户 -->
    <el-dialog v-model="createVisible" title="新增用户" width="460px" align-center>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="72px">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="createForm.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="账号" prop="account">
          <el-input v-model="createForm.account" placeholder="字母 / 数字 / 下划线" />
        </el-form-item>
        <el-form-item label="部门" prop="departmentId">
          <el-select v-model="createForm.departmentId" placeholder="请选择部门" class="full-width">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色" prop="roleId">
          <el-select v-model="createForm.roleId" placeholder="请选择角色" class="full-width">
            <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" placeholder="用于接收账号与任务通知" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">创建用户</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户 -->
    <el-dialog v-model="editVisible" title="编辑用户" width="460px" align-center>
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="72px">
        <el-form-item label="部门" prop="departmentId">
          <el-select v-model="editForm.departmentId" placeholder="请选择部门" class="full-width">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="请输入邮箱" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色指派 -->
    <el-dialog v-model="roleVisible" title="角色指派" width="420px" align-center>
      <p class="dialog-desc">
        为「{{ roleTarget?.name }}」指派新角色，下一请求即按新角色鉴权。
      </p>
      <el-select v-model="roleSelected" placeholder="请选择角色" class="full-width">
        <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
      </el-select>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSubmitting" @click="submitRoleAssign">指派角色</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.keyword-input {
  width: 240px;
}
.create-btn {
  margin-left: auto;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.row-ops {
  opacity: 0;
  transition: opacity 120ms ease-out;
  white-space: nowrap;
}
:deep(.el-table__row:hover) .row-ops {
  opacity: 1;
}
.empty-tip {
  color: #8A97A8;
  font-size: 13px;
  padding: 32px 0;
}
.full-width {
  width: 100%;
}
.dialog-desc {
  font-size: 13px;
  color: #5E6D82;
  margin: 0 0 16px;
}
</style>
