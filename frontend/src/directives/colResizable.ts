import type { Directive } from 'vue'

/**
 * el-table 列宽拖拽指令：v-col-resizable。
 *
 * Element Plus 表格原生不支持列宽拖拽。本指令在表头每个单元格右缘注入
 * 拖拽手柄，拖动时同步修改表头与表体两个 colgroup 中对应 col 的宽度
 * （el-table 表头表体是两张独立 table，各有 colgroup，必须同步改）。
 *
 * 通过 MutationObserver 监听表头变化：列由异步数据渲染（如权限矩阵的
 * 角色列）导致表头重绘时，手柄会自动补挂。所有列均可拖拽，最小列宽 60px。
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
  const headerCols = el.querySelectorAll<HTMLElement>('.el-table__header-wrapper colgroup col')
  // 关键：el-table 的表体是独立的 table，有自己的 colgroup，必须同步改（否则只动表头）
  const bodyCols = el.querySelectorAll<HTMLElement>('.el-table__body-wrapper colgroup col')
  if (!headerRow || headerCols.length === 0) return

  const ths = Array.from(headerRow.children) as HTMLElement[]

  ths.forEach((th, index) => {
    // 已挂过手柄的跳过（重复挂载由 MutationObserver 触发，幂等靠这里保证）
    if (index >= headerCols.length || th.querySelector('.tf-col-resize-handle')) return

    const handle = document.createElement('div')
    handle.className = 'tf-col-resize-handle'
    handle.style.cssText = HANDLE_STYLE
    th.style.position = 'relative'

    handle.addEventListener('mousedown', (e: MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startWidth = th.offsetWidth
      const colIndex = index

      const onMove = (ev: MouseEvent) => {
        const width = Math.max(MIN_WIDTH, startWidth + ev.clientX - startX)
        // 表头与表体的 col 同步调宽（表头可能重渲染，拖动时实时重取）
        const hc = el.querySelectorAll<HTMLElement>('.el-table__header-wrapper colgroup col')
        const bc = el.querySelectorAll<HTMLElement>('.el-table__body-wrapper colgroup col')
        if (hc[colIndex]) hc[colIndex].style.width = `${width}px`
        if (bc[colIndex]) bc[colIndex].style.width = `${width}px`
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
  /**
   * 表头渲染完成后注入手柄，并用 MutationObserver 监听表头子树：
   * 异步列渲染（v-for 列晚于首帧出现）导致表头重绘时自动补挂。
   */
  mounted(el) {
    // el-table 表头异步渲染，延迟一帧确保首帧 DOM 就位
    requestAnimationFrame(() => setupHandles(el))
    const observer = new MutationObserver(() => setupHandles(el))
    const headerWrapper = el.querySelector('.el-table__header-wrapper')
    if (headerWrapper) {
      observer.observe(headerWrapper, { childList: true, subtree: true })
    }
    // 指令卸载时断开观察，防泄漏
    ;(el as HTMLElement & { __tfColResizeObserver?: MutationObserver }).__tfColResizeObserver =
      observer
  },
  unmounted(el) {
    const observer = (el as HTMLElement & { __tfColResizeObserver?: MutationObserver })
      .__tfColResizeObserver
    observer?.disconnect()
  },
}
