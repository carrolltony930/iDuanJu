// 小黄书 xChina · i短剧 idj1 插件
//
// 站点是服务端渲染的 HTML（不是 SPA），但 Cloudflare 会按「请求指纹」拦非浏览器流量：
// 只带 UA 会被 403，必须同时带 sec-ch-ua / Sec-Fetch-* 这组头（实测过）。
// 播放地址在详情页的 #video-player[data-src] 里，是 /hls/{id}/master.m3u8。
// 分页是路径式：/videos/new/2、/video-category/cn/2；搜索用 ?page=N。

const SITE = 'https://xchina.co';

const BROWSER_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  'sec-ch-ua': '"Chromium";v="131", "Not_A Brand";v="24"',
  'sec-ch-ua-mobile': '?0',
  'sec-ch-ua-platform': '"Windows"',
  'Sec-Fetch-Dest': 'document',
  'Sec-Fetch-Mode': 'navigate',
  'Sec-Fetch-Site': 'none',
  'Sec-Fetch-User': '?1',
  'Upgrade-Insecure-Requests': '1'
};

// 站点固定的 5 个视频分类（/categories 里确认过）
const CATEGORIES = [
  { id: 'cat:cn', name: '国产' },
  { id: 'cat:jav', name: '日本AV' },
  { id: 'cat:movie', name: '电影' },
  { id: 'cat:ai', name: 'AI视频' },
  { id: 'cat:other', name: '其他' }
];

function clean(s) {
  return String(s == null ? '' : s)
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ')
    .trim();
}

async function getHtml(path) {
  const res = await host.http.request({ url: SITE + path, headers: BROWSER_HEADERS, timeout: 20000 });
  const body = typeof res.body === 'string' ? res.body : '';
  if (res.status === 403 || body.indexOf('Just a moment') >= 0 || body.indexOf('you have been blocked') >= 0) {
    throw host.error('E_CF_REQUIRED', '站点拦截了请求（Cloudflare）');
  }
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return body;
}

// 列表页卡片：<a href="/video/{id}" ...><img alt="标题"> … </a>
function parseCards(html) {
  const out = [];
  const seen = {};
  const re = /<a[^>]+href="\/video\/([0-9a-f]{10,16})"[^>]*>([\s\S]{0,1200}?)<\/a>/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const id = m[1];
    if (seen[id]) continue;
    const block = m[2];
    let title = '';
    const alt = block.match(/<img[^>]+alt="([^"]*)"/);
    if (alt) title = clean(alt[1]);
    if (!title) {
      // 搜索/相关列表：标题在 span 里，取最长的文本段
      const texts = block.match(/>([^<>]{4,120})</g) || [];
      let best = '';
      for (const t of texts) {
        const v = clean(t.slice(1, -1));
        if (v.length > best.length && !/^\d+:\d+$/.test(v)) best = v;
      }
      title = best;
    }
    if (!id || !title) continue;
    seen[id] = 1;
    const dur = block.match(/(\d{1,3}:\d{2})/);
    out.push({
      id: id,
      title: title,
      cover: 'https://r2.xchina.download/cover/' + id + '.webp',
      remarks: dur ? dur[1] : ''
    });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length > 0 };
}

function pagePath(kind, slug, page) {
  const n = Number(page || 1);
  if (kind === 'new') return n <= 1 ? '/videos/new' : '/videos/new/' + n;
  return n <= 1 ? '/video-category/' + slug : '/video-category/' + slug + '/' + n;
}

function videoId(input) {
  const s = String((input && (input.episodeId || input.id)) || '');
  const m = s.match(/[0-9a-f]{10,16}/);
  return m ? m[0] : '';
}

export default {
  async home() {
    const html = await getHtml(pagePath('new', '', 1));
    return {
      categories: [{ id: 'new', name: '最新' }].concat(CATEGORIES),
      items: parseCards(html),
      hasMore: true
    };
  },

  async category(input) {
    const id = String(input.id || 'new');
    const page = Number(input.page || 1);
    const slug = id.indexOf('cat:') === 0 ? id.slice(4) : '';
    const html = await getHtml(slug ? pagePath('cat', slug, page) : pagePath('new', '', page));
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Number(input.page || 1);
    if (!kw) return { items: [], page: 1, hasMore: false };
    const html = await getHtml('/search?q=' + encodeURIComponent(kw) + '&type=video&page=' + page);
    return pageResult(parseCards(html), page);
  },

  async detail(input) {
    const id = videoId(input);
    if (!id) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await getHtml('/video/' + id);
    const src = html.match(/id="video-player"[^>]*data-src="([^"]+)"/);
    if (!src) throw host.error('E_PARSE', '详情页里没有播放地址');
    const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/);
    const title = h1 ? clean(h1[1]) : id;
    const poster = html.match(/data-poster="([^"]+)"/);
    const dur = html.match(/时长\s*([0-9:]+)/);
    const tags = [];
    const m = html.match(/<dt class="text-subtle">系列<\/dt>[\s\S]{0,300}?<\/dd>/);
    if (m) for (const t of m[0].match(/>([^<>]{2,12})</g) || []) {
      const v = clean(t.slice(1, -1));
      if (v && tags.indexOf(v) < 0) tags.push(v);
    }
    return {
      title: title,
      cover: poster ? poster[1] : 'https://r2.xchina.download/cover/' + id + '.webp',
      desc: tags.join(' · '),
      tags: tags,
      lines: [{ name: '默认', episodes: [{ id: id, name: dur ? '时长 ' + dur[1] : title }] }]
    };
  },

  async play(input) {
    const id = videoId(input);
    if (!id) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const url = SITE + '/hls/' + id + '/master.m3u8';
    return {
      streams: [{
        name: '官方 HLS',
        url: url,
        format: 'hls',
        headers: { 'Referer': SITE + '/' }
      }]
    };
  }
};
