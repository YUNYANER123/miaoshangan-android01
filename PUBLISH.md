# 部署与发布清单

把网页版「喵上岸」打包成可安装的安卓 APK，并让 APK 里的 AI 走你自己的 DeepSeek。
按顺序做，全程免费（DeepSeek 按量付费，日常几毛钱）。

---

## ① 部署 AI 代理（Cloudflare Worker）

两条路，任选其一。**推荐网页版**，不用装任何命令行工具。

### 路线 A：Cloudflare 网页版（最简单，零命令行）

1. 注册 Cloudflare：https://dash.cloudflare.com/sign-up （免费）
2. 注册 DeepSeek 并创建 Key：https://platform.deepseek.com → 「API Keys」→ 新建，复制 `sk-xxxx`
3. Cloudflare 控制台 → 左侧 **Workers & Pages** → **Create** → **Create Worker** → 起名（如 `kaoyan-ai-proxy`）→ **Deploy**
4. 点 **Edit code**，把本仓库 `ai-proxy/worker.js` 的全部内容粘进去 → **Deploy**
5. 回到该 Worker → **Settings → Variables and Secrets** → **Add** → 类型选 **Secret**，名称 `DEEPSEEK_API_KEY`，值填你的 `sk-xxxx` → 保存
6. 复制 Worker 的访问地址，形如 `https://kaoyan-ai-proxy.<你的子域>.workers.dev`

### 路线 B：wrangler 命令行

```bash
cd ai-proxy
npm install -g wrangler
wrangler login                                   # 浏览器授权 Cloudflare
wrangler secret put DEEPSEEK_API_KEY             # 粘贴 sk-xxxx
wrangler deploy                                  # 输出 https://kaoyan-ai-proxy.<子域>.workers.dev
```

> 详细说明见 [`ai-proxy/README.md`](ai-proxy/README.md)。

---

## ② 构建 APK

### 方式 A：GitHub Actions（推荐，无需本机 Android Studio）

1. 本仓库 **Actions** 标签页 → 左侧 **Build Android APK** → 右侧 **Run workflow** → 运行
   （push 到 main 也会自动触发一次）
2. 跑完（约 5–8 分钟）进入该次运行 → 底部 **Artifacts** → 下载 **miao-app-debug.apk**
3. 把 APK 传到手机上安装（首次需在系统设置里允许「安装未知来源应用」）

### 方式 B：本机 Android Studio

```bash
npm install
git clone --depth 1 https://github.com/YUNYANER123/kaoyan28.git _web
mkdir www && cp -r _web/kaoyan/. www/ && rm -rf _web
npx cap add android
npx cap sync android
npx cap open android     # Android Studio → Build → Generate Signed Bundle / APK
```

---

## ③ 在 App 里填入代理地址

安装后打开 App：

**设置 → 🤖 AI 生成代理（自建）**

- **代理地址**：`https://kaoyan-ai-proxy.<你的子域>.workers.dev/v1/chat/completions`
- **模型名**：`deepseek-chat`
- 点 **测试连接**，出现「✅ 连接成功」即可

之后各模块的「🚀 扩充题库」就会走你自己的 DeepSeek。

---

## 更新网页后如何同步到 APK

APK 构建时是**实时**从网页仓库 `kaoyan28` 拉取最新代码的，所以：

网页更新 → 推送 `kaoyan28` → 到本仓库再跑一次 **Build Android APK** → 得到最新 APK。

---

## 常见问题

- **测试连接失败**：检查 Worker 地址是否带 `/v1/chat/completions` 后缀；确认 Worker 里 `DEEPSEEK_API_KEY` 是 **Secret** 类型且值正确。
- **AI 回复 401/402**：DeepSeek 账户余额不足或 Key 失效，去平台重新生成 Key 并更新 Worker 变量。
- **网页版（GitHub Pages）用不了 AI**：这是正常的，官方免费云端 LLM 只允许 `*.workbuddy.host` 调用；APK 走的正是本仓库这套自建代理，不受此限制。
