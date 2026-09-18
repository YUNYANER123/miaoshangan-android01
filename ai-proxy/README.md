# AI 代理（Cloudflare Worker）

这个 Worker 是「独立 APK 里也能用 AI」的关键：它替 App 拿着你的 DeepSeek Key 去调用大模型，
Key 只存在于服务端，不会进安装包，别人反编译 APK 也拿不到。

> 📱 **能否用手机完成？** 可以。第 ① 节「路线 A（Cloudflare 网页版）」全程在**手机浏览器**里点就行，
> **不需要在电脑上装任何东西**（也不用装 wrangler）。唯一要打字的地方是「把 worker.js 全文粘进编辑器」——
> 用下面这个原始链接打开，再长按全选复制最省事：
> `https://raw.githubusercontent.com/YUNYANER123/miaoshangan-android01/main/ai-proxy/worker.js`
> 「路线 B（wrangler 命令行）」才需要电脑，可忽略。

## 1. 准备 DeepSeek Key

- 打开 https://platform.deepseek.com ，注册并充值（新账号有免费额度，个人用很省）。
- 在「API Keys」页面创建一个 Key，形如 `sk-xxxx`。**只在这一步看得见，请先复制保存。**

## 2. 安装并登录 Wrangler

```bash
npm install -g wrangler
wrangler login          # 浏览器里授权你的 Cloudflare 账号（没有账号先去 cloudflare.com 免费注册）
```

## 3. 把 Key 存为机密变量

在本目录执行（会交互式让你粘贴 Key，**不会**写进任何文件）：

```bash
wrangler secret put DEEPSEEK_API_KEY
```

## 4. 部署

```bash
wrangler deploy
```

部署成功后会输出一个地址，类似：

```
https://kaoyan-ai-proxy.<你的子域>.workers.dev
```

## 5. 在 App 里填写

打开安卓 App → 设置 → **AI 生成代理（自建）**：

- **代理地址** 填：`https://kaoyan-ai-proxy.<你的子域>.workers.dev/v1/chat/completions`
- **模型名** 填：`deepseek-chat`

点「测试连接」，看到「✅ 连接成功」即可。之后各模块的「🚀 扩充题库」就会走你自己的 DeepSeek。

## 费用

DeepSeek `deepseek-chat` 约 ¥1 / 百万 tokens，日常刷题库成本极低。Worker 本身在 Cloudflare
免费额度内，个人用基本 0 成本。

---

## 网页版（GitHub Pages）也能用同一个 Worker

这个 Worker **不是 APK 专属**——你的网页版（`https://YUNYANER123.github.io/kaoyan28/`）同样能用，
而且同样**完全不依赖 workbuddy**。原理：网页版代码里已内置「自建 AI 代理」开关，只要当前域名不是
`*.workbuddy.host`，就会改走你配置的代理。所以**一次部署，APK 和网页版共用**。

网页版配置方法（和 App 内一模一样）：
1. 部署好本 Worker，拿到 `https://kaoyan-ai-proxy.<你的子域>.workers.dev`
2. 打开网页版 → **设置 → 🤖 AI 生成代理（自建）**
3. 代理地址填 `https://kaoyan-ai-proxy.<你的子域>.workers.dev/v1/chat/completions`，模型名 `deepseek-chat`
4. 点「测试连接」，✅ 后即可在各模块用「🚀 扩充题库」

> 说明：WorkBuddy 官方托管的版本由 WorkBuddy 云提供，**它的 AI 供应商改不了**，
> 用的是 WorkBuddy 免费云（只授权 `*.workbuddy.host`）。想彻底脱离 workbuddy 用 AI，请用你自己
> 的 GitHub Pages 版本（上面那个 `github.io` 地址），那才是「你的」站点。
