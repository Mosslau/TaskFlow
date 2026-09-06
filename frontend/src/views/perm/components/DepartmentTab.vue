<script setup lang="ts">
import { onMounted, reactive, ref, shallowRef, useTemplateRef } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createDepartmentApi,
  deleteDepartmentApi,
  fetchDepartmentsApi,
  resolveApiError,
  updateDepartmentApi,
  type DepartmentItem,
} from '../../../api/auth'
import { formatDateTime } from '../../../utils/format'

/**
 * 部门标签页：部门列表 + 新增 / 编辑（仅名称）/ 删除（3008 兜底）。
 * 接口：auth-user-service 3.3（权限点 manageUser）。
 */
const loading = shallowRef(false)
const departments = ref<DepartmentItem[]>([])
/** 部门名称关键字（模糊筛选） */
const keyword = shallowRef('')

async function loadDepartments() {
  loading.value = true
  try {
    departments.value = await fetchDepartmentsApi(keyword.value.trim() || undefined)
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    loading.value = false
  }
}

onMounted(loadDepartments)

// ---------- 新增 / 编辑 ----------
const dialogVisible = shallowRef(false)
const dialogSubmitting = shallowRef(false)
const dialogMode = shallowRef<'create' | 'edit'>('create')
const editTarget = shallowRef<DepartmentItem | null>(null)
const formRef = useTemplateRef<FormInstance>('formRef')
const form = reactive({ name: '' })

const rules: FormRules = {
  name: [{ required: true, message: '请输入部门名称', trigger: 'blur' }],
}

function openCreate() {
  dialogMode.value = 'create'
  editTarget.value = null
  form.name = ''
  dialogVisible.value = true
}

function openEdit(row: DepartmentItem) {
  dialogMode.value = 'edit'
  editTarget.value = row
  form.name = row.name
  dialogVisible.value = true
}

async function submitDialog() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  dialogSubmitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createDepartmentApi(form.name.trim())
      ElMessage.success('已创建部门')
    } else if (editTarget.value) {
      await updateDepartmentApi(editTarget.value.id, form.name.trim())
      ElMessage.success('已保存')
    }
    dialogVisible.value = false
    loadDepartments()
  } catch (error) {
    ElMessage.error(resolveApiError(error).message)
  } finally {
    dialogSubmitting.value = false
  }
}

// ---------- 删除 ----------
async function handleDelete(row: DepartmentItem) {
  try {
    await ElMessageBox.confirm(
      `删除后不可恢复；部门「${row.name}」当前 ${row.userCount} 人。`,
      '删除部门',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteDepartmentApi(row.id)
    ElMessage.success('已删除')
    loadDepartments()
  } catch (error) {
    const { code, message, details } = resolveApiError(error)
    if (code === 3008) {
      const count = details?.userCount
      ElMessage.error(
        typeof count === 'number' ? `该部门存在 ${count} 名在职用户，不可删除` : '该部门存在在职用户，不可删除',
      )
    } else {
      ElMessage.error(message)
    }
  }
}
</script>

<template>
  <div class="dept-tab">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="按部门名称搜索"
        clearable
        class="search-input"
        @change="loadDepartments"
        @clear="loadDepartments"
      />
      <el-button v-perm="'manageUser'" type="primary" class="create-btn" @click="openCreate">
        新增部门
      </el-button>
    </div>

    <el-table v-loading="loading" v-col-resizable :data="departments">
      <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
      <el-table-column label="人数" width="120">
        <template #default="{ row }">
          <span class="tf-num">{{ row.userCount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">
          <span class="tf-num">{{ formatDateTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <div class="row-ops">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-tip">还没有部门，点击右上角「新增部门」创建第一个。</div>
      </template>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增部门' : '编辑部门'"
      width="420px"
      align-center
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入部门名称" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogSubmitting" @click="submitDialog">
          {{ dialogMode === 'create' ? '创建部门' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.search-input {
  width: 240px;
}
.create-btn {
  margin-left: auto;
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
</style>
