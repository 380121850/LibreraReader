import './foliate/view.js'
import { createTOCView } from './foliate/ui/tree.js'

const $ = document.createElement.bind(document)

export async function mountReader(target, host, { title = '', onBack = () => {} } = {}) {
  host.innerHTML = ''
  const style = document.createElement('style')
  style.textContent = `
    .hr-root { position: absolute; inset: 0; display: flex; flex-direction: column;
      background: #525659; font-family: 'Segoe UI', Arial, sans-serif; }
    .hr-toolbar { height: 38px; flex: none; display: flex; align-items: center; gap: 4px;
      padding: 0 8px; background: #2b2b2b; color: #eee; font-size: 13px;
      box-shadow: 0 1px 4px rgba(0,0,0,.4); z-index: 10; }
    .hr-toolbar button { background: transparent; border: none; color: #eee; font-size: 14px;
      padding: 6px 9px; border-radius: 4px; cursor: pointer; }
    .hr-toolbar button:hover { background: #444; }
    .hr-toolbar .sep { width: 1px; height: 20px; background: #555; margin: 0 4px; }
    .hr-title { color: #ccc; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
      max-width: 34vw; }
    .hr-progress { flex: 1; display: flex; align-items: center; gap: 8px; min-width: 60px; }
    .hr-progress input[type=range] { flex: 1; max-width: 320px; }
    .hr-percent { color: #aaa; font-size: 12px; min-width: 40px; text-align: center; }
    .hr-main { position: relative; flex: 1; overflow: hidden; background: #ffffff; }
    foliate-view { position: absolute; inset: 0; }
    .hr-side { position: absolute; top: 0; bottom: 0; left: 0; width: min(300px, 80vw);
      background: #262626; color: #ddd; z-index: 20; overflow: auto; padding: 10px 6px;
      border-right: 1px solid #444; display: none; font-size: 14px; }
    .hr-side.open { display: block; }
    .hr-side h3 { margin: 6px 8px; font-size: 13px; color: #999; }
    .hr-side ul { list-style: none; margin: 0; padding-left: 14px; }
    .hr-side > ul { padding-left: 6px; }
    .hr-side li { margin: 2px 0; }
    .hr-side a { color: #ddd; text-decoration: none; display: block; padding: 4px 8px;
      border-radius: 3px; cursor: pointer; }
    .hr-side a:hover { background: #3a3a3a; }
    @media (max-width: 600px) { .hr-title { display: none; } }
  `
  const root = $('div')
  root.className = 'hr-root'
  root.appendChild(style)

  const toolbar = $('div')
  toolbar.className = 'hr-toolbar'
  toolbar.innerHTML = `
    <button class="hr-back" title="返回">←</button>
    <span class="sep"></span>
    <button class="hr-toc" title="目录">☰</button>
    <button class="hr-prev" title="上一页">◀</button>
    <button class="hr-next" title="下一页">▶</button>
    <span class="sep"></span>
    <button class="hr-font-dec" title="减小字号">A−</button>
    <button class="hr-font-inc" title="增大字号">A＋</button>
    <button class="hr-theme" title="日间/夜间切换"></button>
    <span class="sep"></span>
    <span class="hr-title"></span>
    <span class="hr-progress"><input type="range" min="0" max="1000" value="0"><span class="hr-percent">0%</span></span>
  `
  const side = $('div')
  side.className = 'hr-side'
  side.innerHTML = '<h3>目录 Contents</h3>'
  const main = $('div')
  main.className = 'hr-main'
  const view = $('foliate-view')

  root.append(toolbar, side, main)
  main.appendChild(view)
  host.appendChild(root)

  root.querySelector('.hr-back').addEventListener('click', onBack)
  root.querySelector('.hr-prev').addEventListener('click', () => view.goLeft())
  root.querySelector('.hr-next').addEventListener('click', () => view.goRight())
  root.querySelector('.hr-toc').addEventListener('click', () => side.classList.toggle('open'))

  // day / night theme (persisted)
  let theme = localStorage.getItem('hr-theme') === 'dark' ? 'dark' : 'light'
  const themeBtn = root.querySelector('.hr-theme')
  const applyThemeBtn = () => { themeBtn.textContent = theme === 'dark' ? '☀️' : '🌙' }
  themeBtn.addEventListener('click', () => {
    theme = theme === 'dark' ? 'light' : 'dark'
    localStorage.setItem('hr-theme', theme)
    applyThemeBtn()
    applyFont()
  })
  applyThemeBtn()

  let fontSize = 17
  const applyFont = () => {
    const dark = theme === 'dark'
    view.renderer.setStyles(`
      html { font-size: ${fontSize}px !important; color-scheme: ${theme}; }
      ${dark
        ? 'html, body { background: #1b1b1b !important; color: #c9c9c9 !important; } a:link { color: #9fb4ff !important; }'
        : 'html, body { background: #ffffff !important; color: #1a1a1a !important; }'}
      p, li, blockquote, dd, td, th, figcaption { line-height: 1.6; }
    `)
    main.style.background = dark ? '#1b1b1b' : '#ffffff'
  }
  root.querySelector('.hr-font-dec').addEventListener('click', () => { fontSize = Math.max(12, fontSize - 1); applyFont() })
  root.querySelector('.hr-font-inc').addEventListener('click', () => { fontSize = Math.min(30, fontSize + 1); applyFont() })

  const slider = root.querySelector('.hr-progress input')
  const percent = root.querySelector('.hr-percent')
  slider.addEventListener('input', () => view.goToFraction(parseFloat(slider.value) / 1000))
  view.addEventListener('relocate', e => {
    const { fraction, tocItem } = e.detail
    slider.value = Math.round(fraction * 1000)
    percent.textContent = Math.round(fraction * 100) + '%'
    if (tocItem?.label) root.querySelector('.hr-title').textContent = title ? title + ' · ' + tocItem.label.trim() : tocItem.label.trim()
  })
  view.addEventListener('load', e => {
    const meta = e.detail?.metadata
    const t = meta?.title
    if (t) {
      root.querySelector('.hr-title').textContent = typeof t === 'string' ? t : Object.values(t)[0]
    }
  })
  document.addEventListener('keydown', e => {
    if (e.key === 'ArrowLeft') view.goLeft()
    if (e.key === 'ArrowRight') view.goRight()
  })

  await view.open(target)
  applyFont()
  // foliate does not render the first section until a navigation happens
  await view.goToFraction(0).catch(() => {})

  const toc = view.book?.toc
  if (toc && toc.length) {
    const tocView = createTOCView(toc, href => {
      view.goTo(href).catch(() => {})
      side.classList.remove('open')
    })
    side.appendChild(tocView.element)
  } else {
    root.querySelector('.hr-toc').style.display = 'none'
  }
  return { view, close: () => { host.innerHTML = '' } }
}
