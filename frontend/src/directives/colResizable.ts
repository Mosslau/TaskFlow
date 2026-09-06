import type { Directive } from 'vue'

/**
 * el-table 列宽拖拽指令：v-col-resizable。
 *
 * Element Plus 表格原生不支持列宽拖拽。本指令在表头每个单元格右缘注入
 * 拖拽手柄，拖动时同步修改 colgroup 中对应 col 的宽度（el-table 列宽
 * 最终由 colgroup 决定），最小列宽 60px，最后一列（通常为操作列）不加手柄。
 *
 * 用法：<el-table v-col-resizable ...>
 */

/** 最小列宽（px） */
const MIN_WIDTH = 60

/** 手柄样式（内联，避免依赖 scoped 样式穿透） */
const HANDLE_STYLE =
  'position:absolute;right:0;top:0;bottom:0;width:8px;cursor:col-resize;' +
  'user-select:none;z-index:2;'

function setupHandles(el: HTMLElement) {
  const headerRow = el.querySelector<HTMLElement>('.el-table__header-wrapper thead tr')
  const colgroup = el.querySelector<HTMLElement>('.el-table__header-wrapper colgroup')
  if (!headerRow || !colgroup) return

  const ths = Array.from(headerRow.children) as HTMLElement[]
  const cols = Array.from(colgroup.children) as HTMLElement[]

  ths.forEach((th, index) => {
    // 最后一列（操作列）与已初始化的单元格跳过
    if (index === ths.length - 1 || index >= cols.length) return
    if (th.querySelector('.tf-col-resize-handle')) return

    const handle = document.createElement('div')
    handle.className = 'tf-col-resize-handle'
    handle.style.cssText = HANDLE_STYLE
    th.style.position = 'relative'

    handle.addEventListener('mousedown', (e: MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startWidth = th.offsetWidth
      const col = cols[index]

      const onMove = (ev: MouseEvent) => {
        const width = Math.max(MIN_WIDTH, startWidth + ev.clientX - startX)
        col.style.width = `${width}px`
      }
      const onUp = () => {
        document.removeEventListener('mousemove', onMove)
        document.removeEventListener('mouseup', onUp)
      }
      document.addEventListener('mousemove', onMove)
      document.addEventListener('mouseup', onUp)
    })

    th.appendChild(handle)
  })
}

export const vColResizable: Directive<HTMLElement> = {
  /** 表头渲染完成后注入手柄 */
  mounted(el) {
    // el-table 表头异步渲染，延迟一帧确保 DOM 就位
    requestAnimationFrame(() => setupHandles(el))
  },
}
