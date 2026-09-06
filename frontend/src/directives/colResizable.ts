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
  const headerCols = el.querySelectorAll<HTMLElement>('.el-table__header-wrapper colgroup col')
  // 关键：el-table 的表体是独立的 table，有自己的 colgroup，必须同步改（否则只动表头）
  const bodyCols = el.querySelectorAll<HTMLElement>('.el-table__body-wrapper colgroup col')
  if (!headerRow || headerCols.length === 0) return

  const ths = Array.from(headerRow.children) as HTMLElement[]

  ths.forEach((th, index) => {
    // 最后一列（操作列）与已初始化的单元格跳过
    if (index === ths.length - 1 || index >= headerCols.length) return
    if (th.querySelector('.tf-col-resize-handle')) return

    const handle = document.createElement('div')
    handle.className = 'tf-col-resize-handle'
    handle.style.cssText = HANDLE_STYLE
    th.style.position = 'relative'

    handle.addEventListener('mousedown', (e: MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startWidth = th.offsetWidth

      const onMove = (ev: MouseEvent) => {
        const width = Math.max(MIN_WIDTH, startWidth + ev.clientX - startX)
        // 表头与表体的 col 同步调宽
        headerCols[index].style.width = `${width}px`
        if (bodyCols[index]) {
          bodyCols[index].style.width = `${width}px`
        }
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
