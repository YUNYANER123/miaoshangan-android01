// Cloudflare Worker：把 OpenAI 兼容的 /chat/completions 请求转发到 DeepSeek。
// 关键点：DEEPSEEK_API_KEY 只存在于服务端环境变量（wrangler secret put），
// 绝不暴露给浏览器 / 安装包，别人反编译 APK 也拿不到你的 Key。
//
// 部署见同目录 README.md。

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization'
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // CORS 预检
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (url.pathname !== '/v1/chat/completions') {
      return new Response('Not Found', { status: 404, headers: corsHeaders() });
    }
    if (request.method !== 'POST') {
      return new Response('Method Not Allowed', { status: 405, headers: corsHeaders() });
    }

    const apiKey = env.DEEPSEEK_API_KEY;
    if (!apiKey) {
      return new Response(JSON.stringify({ error: '服务端未配置 DEEPSEEK_API_KEY' }), {
        status: 500,
        headers: { ...corsHeaders(), 'Content-Type': 'application/json' }
      });
    }

    let payload;
    try {
      payload = await request.json();
    } catch (e) {
      return new Response(JSON.stringify({ error: '请求体不是合法 JSON' }), {
        status: 400,
        headers: { ...corsHeaders(), 'Content-Type': 'application/json' }
      });
    }

    const stream = !!payload.stream;
    const upstream = await fetch('https://api.deepseek.com/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + apiKey,
        Accept: stream ? 'text/event-stream' : 'application/json'
      },
      body: JSON.stringify(payload)
    });

    const headers = corsHeaders();
    if (stream) {
      // 直接透传 SSE 流，实现与官方一致的「边生成边显示」
      headers['Content-Type'] = 'text/event-stream; charset=utf-8';
      headers['Cache-Control'] = 'no-cache';
      return new Response(upstream.body, { status: upstream.status, headers });
    }
    // 非流式：原样转发 JSON（用于 App 里的「测试连接」）
    headers['Content-Type'] = 'application/json';
    return new Response(upstream.body, { status: upstream.status, headers });
  }
};
