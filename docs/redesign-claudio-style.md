# 前端重设计规范：Claudio 风格 reskin（Aftertaste FM Web）

本文件是把 `apps/web` 从当前「Apple 浅色极简」风格重做为 **「复古未来 / 终端电台」风格** 的**完整实施规范**。
灵感来源：Claudio（@JiaweiShen2568 转发的 @秒秒Guo 作品）。

性质：**纯视觉层 reskin**。除「新增点阵时钟 hero 组件」「解锁深色模式」外，不改动业务逻辑、API、状态管理、PWA（SW/manifest 仅 `theme_color` 跟随更新）。

---

## 0. 设计目标

一句话：**把界面做成一台「被重新想象的复古 LED 电台收音机」—— 纯黑、点阵字、等宽 UI、边缘辉光、克制到只有一个状态绿。**

三个不可妥协的原则：

1. **未来感来自字体与光**，不来自装饰 —— 点阵显示字 + 等宽字 + 紫色辉光描边。
2. **简约来自「元素少 + 对比强」** —— 不加渐变插画、不加多彩按钮，层级靠字号/明度/留白。
3. **氛围来自「设备感」** —— 整个 app 是一块浮在纯黑上的「面板」，像一台机器的正面板。

---

## 1. 设计语言总览（来自参考图拆解）

| 元素 | 参考图表现 | 落地方式 |
|---|---|---|
| 点阵 LED 字体 | 标志 `Claudio`、巨型时钟 `21:11` | 显示字体，只用于品牌字 + 时钟 + 大数字 |
| 等宽 UI 字体 | `QUEUE` `LIVE` `0 TRACKS` `CONNECTED` `▶ REPLAY` 全部等宽 | UI 字体，几乎所有界面文字 |
| 巨型时钟 hero | 点阵数字 + `Monday 20 APR 2026` + `● ON AIR`，背景点阵网格 | **新增组件**，见 §6 |
| 纯黑底 + 面板 | app 是一块圆角面板，浮在纯黑上，边缘有紫/靛辉光 | `body` 纯黑，`.shell` 内一块 `.device-panel` |
| 单一状态色 | 绿色只出现在「● ON AIR / ● 在线 / 当前播放行」 | `--accent-live`，严格限制使用范围 |
| 点阵网格纹理 | 时钟区背景的细点网格 | `radial-gradient` 平铺 |
| 波形可视化 | 播放时竖条波形 | 见 §6，可后置 |
| 队列行 | 行号等宽、歌名左 / 歌手右对齐、当前行左侧绿色描边 | 见 §7 |
| 聊天 | 深色气泡、`● Claudio` 绿点在线、`Say something to the DJ...` 输入 | 见 §7 |
| 页脚 | `CLAUDIO FM. ............ CONNECTED.` 撑满两端 | 见 §6 |
| 控制条 | 文字标签按钮（HIDE/FAV/VOL）混图标，幽灵描边风 | 见 §7 |

---

## 2. 字体规范

### 2.1 显示字体（点阵 / LED）

- **用途**：仅限品牌字「Aftertaste FM」、巨型时钟、大号数字（如时长、`ON AIR` 不算）。**不要**用于正文，可读性差。
- **首选**：`DotGothic16`（Google Fonts，免费可商用，点阵观感接近参考图）。
- **备选**：`Micro 5`、`Pixelify Sans`（Google Fonts）；若要更接近 LED 段码可考虑 `DSEG`（自托管）。
- **加载**：自托管到 `apps/web/public/fonts/`，用 `@font-face` + `font-display: swap`，**不要**用 Google Fonts CDN（PWA 离线要求 + 隐私）。
- CSS 变量：`--font-display`。

### 2.2 UI 字体（等宽）

- **用途**：几乎所有界面文字 —— 导航、标签、队列、聊天、按钮、状态条。
- **首选**：`JetBrains Mono`；备选 `IBM Plex Mono`、`Space Mono`。
- **同样自托管**到 `public/fonts/`。
- CSS 变量：`--font-mono`。

### 2.3 正文兜底

- 歌词、Agent 长文本、用户输入：等宽长段落易疲劳。允许这部分用 `--font-text`（系统 sans：`-apple-system, "SF Pro Text", "Segoe UI", sans-serif`），但**字号、行高要和等宽体系对齐**，避免割裂。
- 决策原则：**结构性/标签性文字 = 等宽；大段阅读性文字 = sans 兜底**。

### 2.4 字重与字号

- 等宽字重控制在 `400 / 500 / 700` 三档。
- 标签类文字统一 `letter-spacing: 0.08em ~ 0.12em` + `text-transform: uppercase`（参考图所有 `QUEUE`/`LIVE` 都是大写宽间距）。

---

## 3. 颜色系统

全部走 CSS 自定义属性，定义在 `:root`（深色为默认）。**先做深色，浅色后置**（见 §9）。

```
/* 背景层 —— 由外到内逐层提亮 */
--bg-void:        #000000;   /* body，纯黑 */
--bg-panel:       #0c0d10;   /* 主面板 .device-panel */
--bg-elevated:    #15161a;   /* 卡片 / 气泡 / 输入框 */
--bg-inset:       #08090b;   /* 时钟显示区等「凹下去」的区域 */

/* 描边 */
--line-soft:      rgba(255,255,255,0.08);
--line-strong:    rgba(255,255,255,0.16);

/* 文字 */
--text-primary:   #e8e8ea;
--text-secondary: #8a8b90;
--text-faint:     #4e4f54;   /* 页脚、占位、次要标签 */

/* 强调色 —— 严格限制用途 */
--accent-live:    #4ade80;   /* 仅：ON AIR / 在线点 / 当前播放行描边 */
--accent-glow:    #6d5dfc;   /* 仅：面板边缘辉光、focus 光圈 */

/* 辉光 */
--glow-panel:  0 0 60px -12px rgba(109,93,252,0.45), 0 0 1px rgba(109,93,252,0.6);
--glow-focus:  0 0 0 3px rgba(109,93,252,0.35);
```

使用纪律：

- **绿色（`--accent-live`）是状态色,不是品牌色** —— 只标记「正在直播 / 在线 / 当前曲目」。按钮、链接、hover **一律不用绿色**。
- **紫色（`--accent-glow`）只做光,不做填充** —— 不存在「紫色按钮」「紫色文字」，只有辉光和 focus 圈。
- 普通可点击元素：默认 `--text-secondary`，hover/active 提亮到 `--text-primary` + `--line-strong` 描边。**靠明度变化表达交互，不靠颜色。**

---

## 4. 形状、间距、动效

### 4.1 形状

- 主面板 `.device-panel`：`border-radius: 16px`，`1px solid --line-soft`，`box-shadow: --glow-panel`。
- 内部卡片 / 气泡 / 输入框：`border-radius: 8px`。
- 按钮：`border-radius: 6px`，幽灵风（透明底 + 描边），不是实心填充。
- 时钟显示区：`border-radius: 10px`，背景 `--bg-inset` + 点阵网格纹理。

### 4.2 间距

- 基准 4px 栅格：`4 / 8 / 12 / 16 / 24 / 32 / 48`。
- 面板内边距 `clamp(16px, 3vw, 32px)`。
- 简约靠留白：宁可多留空，不要塞满。

### 4.3 动效

克制但精准，全部 `prefers-reduced-motion` 下关闭。

| 场景 | 动效 |
|---|---|
| 切歌 / 切视图 | 150–200ms `opacity` + 2–4px `translateY` 淡入 |
| 时钟数字跳秒 | 无动效或极短（避免分心）；冒号 `● ON AIR` 的点可做 1s 呼吸 |
| 在线绿点 | 2s `opacity` 呼吸（0.4 → 1） |
| 按钮 hover | 120ms 明度 + 描边过渡 |
| 波形 | 见 §6 |
| focus | 直接套 `--glow-focus`，无过渡 |

---

## 5. 文件改动清单

| 文件 | 改动 |
|---|---|
| `apps/web/public/fonts/` | **新增**，放自托管字体 + `@font-face` |
| `apps/web/src/styles/tokens.css` | **新增**，§2/§3/§4 的所有 CSS 变量集中在此，被 `styles.css` 第一个 import |
| `apps/web/src/styles.css` | 调整 import 顺序：`tokens.css` 最先 |
| `apps/web/src/styles/base.css` | 重写：纯黑 body、`.device-panel`、`.shell`、字体、focus、按钮基样式；**删除 `color-scheme: light` 写死** |
| `apps/web/src/styles/player.css` | 重写：控制条幽灵按钮、面板化 |
| `apps/web/src/styles/player-playback.css` | 重写：进度条、时长（点阵字）、波形容器 |
| `apps/web/src/styles/views.css` | 重写：队列行、列表视图 |
| `apps/web/src/styles/views-detail.css` | 重写：详情视图 |
| `apps/web/src/styles/dialogs.css` | 重写：弹窗深色化、辉光描边 |
| `apps/web/src/styles/clock.css` | **新增**，时钟 hero 样式 |
| `apps/web/src/components/ClockHero.tsx` | **新增**，见 §6 |
| `apps/web/src/components/AppNav.tsx` | 改 class / 文案为大写等宽标签；逻辑不动 |
| `apps/web/src/components/StatusStrip.tsx` | 改为页脚式 `AFTERTASTE FM. ... CONNECTED.`；逻辑不动 |
| `apps/web/src/components/PlaybackPanel.tsx` | 套新 class；按钮文案改大写；逻辑不动 |
| `apps/web/src/components/QueuePanel.tsx` | 套新 class；当前行绿色描边；逻辑不动 |
| `apps/web/src/components/AgentPanel.tsx` | 套新 class；气泡深色化、`● 在线` 绿点；逻辑不动 |
| `apps/web/src/components/LyricsPanel.tsx` | 套新 class；逻辑不动 |
| `apps/web/src/components/PwaUpdatePrompt.tsx` | `.pwa-toast` 系列样式深色化（见 §8） |
| `apps/web/src/App.tsx` | 在布局顶部挂 `<ClockHero />`；包一层 `.device-panel`；逻辑不动 |
| `apps/web/vite.config.ts` | `VitePWA` 的 `manifest.theme_color` / `background_color` 改为 `#000000` |
| `apps/web/index.html` | `<meta name="theme-color">` 改为 `#000000` |

> **约束**：除 `ClockHero.tsx`、`App.tsx` 挂载、`StatusStrip` 文案结构外，所有 `.tsx` 改动应**只动 className 与静态文案**，不得改 props、state、hooks、事件处理。

---

## 6. 新增组件：ClockHero

参考图最抓眼的部分，当前 app 没有，必须新增。

### 6.1 结构

```
.clock-hero (背景 --bg-inset + 点阵网格纹理)
 ├─ .clock-hero-brand     "AFTERTASTE FM"  —— --font-display，小号
 ├─ .clock-hero-time      "21:11"          —— --font-display，超大号（clamp 64–120px）
 ├─ .clock-hero-date      "Monday · 20 MAY 2026"  —— --font-mono，大写宽间距
 └─ .clock-hero-status    "● ON AIR" / "● OFF AIR" —— 绿点 + --font-mono
```

### 6.2 行为

- 每秒更新时间（`setInterval`，组件卸载清理）。
- 时间/日期格式跟随浏览器 locale，但**月份用大写英文缩写**（`MAY`）以贴合参考图。
- `ON AIR` 状态来源：从 `usePlayer` 的播放状态推导（`playback.isPlaying` 或 `playback.currentItem != null`）—— **复用现有 hook，不新建状态**。
- 冒号或状态点做 1s 呼吸动效（`prefers-reduced-motion` 关闭）。
- 纯展示组件，无交互、无副作用（除计时器）。

### 6.3 点阵网格纹理

```css
background-image: radial-gradient(rgba(255,255,255,0.06) 1px, transparent 1px);
background-size: 4px 4px;
```

### 6.4 波形可视化（可后置，列为 Phase 4）

- 优先做「假波形」：一组宽度固定的竖条，CSS `@keyframes` 随机高度循环，仅在 `playback.isPlaying` 时动。成本低、无音频权限问题。
- 「真波形」（Web Audio API `AnalyserNode` 接 `AppAudio` 的 `<audio>`）成本高、涉及 CORS，**本次不做**，留 roadmap。

### 6.5 页脚（StatusStrip 改造）

- 结构：`AFTERTASTE FM.` 居左 ，`CONNECTED. / OFFLINE.` 居右，`--font-mono`、`--text-faint`、大写。
- `CONNECTED / OFFLINE` 复用 `utils/network.ts` 的 `isBrowserOffline()` + online/offline 事件。

---

## 7. 组件级样式细则

### 7.1 AppNav

- 每个导航项：`--font-mono`、大写、`letter-spacing: 0.1em`。
- 默认 `--text-secondary`，当前项 `--text-primary` + 底部 1px `--line-strong` 或左侧标记。
- **不用绿色**标记当前项（绿色留给「直播状态」）。
- 窄屏沿用现有「隐藏文字只留图标」逻辑。

### 7.2 PlaybackPanel / 控制条

- 控制条是一行幽灵按钮：透明底、`1px solid --line-soft`、`--text-secondary`。
- 文字型按钮（`HIDE` `FAV` `VOL`）大写等宽；图标按钮（上一首/播放/下一首/停止）用现有 `lucide-react`。
- 当前曲目标题：可用 `--font-display` 小号或 `--font-mono` 700。
- 时长 `2:19 / 3:27`：`--font-mono`，参考图里时长是等宽数字感。
- 进度条：细轨（2–3px），已播放段 `--text-primary`，未播放 `--line-soft`，**不用绿色**。
- hover：明度提升 + 描边转 `--line-strong`，120ms。

### 7.3 QueuePanel

- 表头 `QUEUE` 居左、`N TRACKS` 居右，大写等宽 `--text-faint`。
- 每行：`[行号] [歌名 ......... 歌手]`，行号与歌手 `--text-secondary`，歌名 `--text-primary`。
- **当前播放行**：左侧 2px `--accent-live` 描边 + 行背景 `--bg-elevated`。这是绿色允许出现的少数位置之一。
- 行 hover：背景 `--bg-elevated`。

### 7.4 AgentPanel（聊天）

- 顶部 `● Claudio`（沿用现有 host 名）+ `LIVE`，绿点呼吸。
- 消息气泡：`--bg-elevated`、`border-radius: 8px`、`--font-text`（长文本可读性）。
- 用户消息与 Agent 消息靠对齐区分（参考图：用户右、Agent 左）。
- 时间戳 `20:08`：`--font-mono`、`--text-faint`。
- 输入框：`--bg-elevated`、占位文案保持现有语义（如 `Say something to the host...`）、`--font-mono`；mic + send 为幽灵图标按钮。
- `▶ REPLAY` 之类操作链接：`--font-mono` 大写小号。

### 7.5 LyricsPanel

- 容器深色化；当前行 `--text-primary`，其它行 `--text-faint`。
- 歌词正文用 `--font-text`（大段阅读）。
- 滚动高亮过渡 200ms，`prefers-reduced-motion` 关闭。

### 7.6 dialogs

- 遮罩 `rgba(0,0,0,0.72)`。
- 弹窗体：`--bg-panel` + `1px solid --line-soft` + `--glow-panel`（轻量版）。
- 主按钮 = 幽灵按钮高对比版（`--text-primary` 描边）；次按钮 = 普通幽灵。**不引入实心彩色按钮。**

---

## 8. PWA 一致性要求

- `vite.config.ts` → `manifest.theme_color` 与 `background_color` 改为 `#000000`。
- `index.html` → `<meta name="theme-color" content="#000000">`。
- 应重新生成应用图标为深色版本（当前 `public/icons/*` 若为浅底需替换；maskable 安全区规则不变）。
- `PwaUpdatePrompt` 的 `.pwa-toast`：背景改 `--bg-elevated`、描边 `--line-strong`、文字走 token、动作按钮改幽灵风；保持 `aria-live`、非阻塞、定位不变。
- SW 缓存策略、注册逻辑、离线降级逻辑**完全不动**。
- 字体文件需进 precache：确认 `public/fonts/*` 被 Workbox `globPatterns` 覆盖（`woff2` 默认在内），否则离线缺字。

---

## 9. 深色 / 浅色模式

- **本次只交付深色**，且为默认。
- `tokens.css` 中颜色变量集中在 `:root`；后续做浅色时新增 `:root[data-theme="light"]` 覆盖一层即可，**组件 CSS 不得硬编码颜色**（全部走变量）—— 这是为浅色预留的唯一要求。
- 参考图的 `DARK / LIGHT` 切换器本次不实现，但 `AppNav` 预留位置。
- 删除 `base.css` 里写死的 `color-scheme: light`，改为 `color-scheme: dark`。

---

## 10. 实施阶段（按此顺序，每阶段可独立验收）

### Phase 1 — 地基（无可见大变化，先打底）
- 自托管字体 + `@font-face`。
- 新建 `tokens.css`，落地全部颜色/字体/间距变量。
- `base.css` 改纯黑底、`.device-panel` 容器、`color-scheme: dark`、focus 光圈、按钮基样式。
- 验收：app 能跑、整体变黑、字体已切换、无布局崩坏。

### Phase 2 — 垂直切片：时钟 Hero + 播放器
- 新增 `ClockHero.tsx` + `clock.css`，挂到 `App.tsx` 顶部。
- 重写 `player.css` / `player-playback.css`，控制条幽灵化、时长点阵化。
- `StatusStrip` 改页脚式。
- 验收：**这一屏要和参考图「神似」** —— 这是判断方向对不对的关键节点，方向不对就在这里调，不要往下铺。

### Phase 3 — 铺开：队列 / 聊天 / 导航 / 歌词 / 弹窗
- 依 §7 逐个组件套样式。
- 验收：全界面风格统一，无浅色残留。

### Phase 4 — 收尾
- 假波形可视化。
- PWA：theme_color、深色图标、`.pwa-toast` 深色化。
- 动效统一过一遍，补 `prefers-reduced-motion`。
- 验收：见 §11。

---

## 11. 验收清单

- [ ] `npm run build:web` 通过，无 TS 报错。
- [ ] 所有组件 `.tsx` 的 diff：除 `ClockHero`、`App.tsx` 挂载、`StatusStrip` 文案结构外，**只有 className 与静态文案变化**，无逻辑改动。
- [ ] 全界面无任何浅色残留（搜不到遗留的 `#fff` 背景硬编码）。
- [ ] 组件 CSS 中**没有硬编码颜色**，全部引用 `tokens.css` 变量。
- [ ] 绿色仅出现在：`ON AIR`、在线点、当前播放行。其它任何地方无绿色。
- [ ] 紫色仅以辉光 / focus 圈形式出现，无紫色填充或文字。
- [ ] 点阵显示字仅用于品牌字、时钟、大号数字；正文未使用点阵字。
- [ ] 时钟每秒更新，`ON AIR` 状态与播放状态联动，组件卸载无计时器泄漏。
- [ ] `prefers-reduced-motion: reduce` 下所有动效关闭。
- [ ] 键盘 focus 可见（`--glow-focus`），Tab 顺序未被破坏。
- [ ] 窄屏（≤ 640px）布局不溢出，`AppNav` 图标降级逻辑仍生效。
- [ ] PWA：`manifest.webmanifest` 与 `<meta theme-color>` 均为 `#000000`；字体文件进了 precache；离线打开无缺字。
- [ ] SW / 离线降级 / API 逻辑与改造前行为一致。
- [ ] 安装后的 PWA 窗口标题栏配色与纯黑界面协调（无白色闪屏）。

---

## 12. 明确不做（防止范围蔓延）

- 不改业务逻辑、API 契约、状态管理、数据流。
- 不做真波形（Web Audio API）—— 仅假波形。
- 不实现 DARK/LIGHT 切换器 —— 仅交付深色，浅色留扩展位。
- 不引入 UI 组件库 / CSS 框架 / CSS-in-JS —— 保持现有「按组件拆分的原生 CSS」结构。
- 不改路由、不加新页面。
- 不动 `netease-adapter`、`radio-server`。
