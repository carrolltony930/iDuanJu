// madou.club（麻豆社）· i短剧 idj1 插件
//
// WordPress 站点：
//   列表  /                    （/page/{n}/ 翻页）
//   分类  /category/{slug}/    （/category/{slug}/page/{n}/ 翻页）
//   搜索  /?s=关键词           （/page/{n}/?s=关键词 翻页）
//   详情  /{slug}.html
//
// 详情页里只有一个 iframe：https://dash.madou.club/share/{id}
// 那个 share 页里写着：
//     var token = "<JWT>";                       // exp - iat = 100 秒
//     var m3u8  = '/videos/{id}/index.m3u8';
//     m3u8 = m3u8 + '?token=' + token;
// token 只有 100 秒，所以必须在播放时现取（不能缓存太久）。
// 分片和 AES-128 密钥都**不需要** token（实测不带 token 也是 200），只有播放列表需要。

const SITE = 'https://madou.club';
const DASH = 'https://dash.madou.club';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9',
  'Referer': SITE + '/'
};

// 分类 slug 是中文的 URL 编码，直接用导航里的原样值
const CATEGORIES = [
  { id: 'latest', name: '最新' },
  { id: 'likes', name: '最多赞' },
  { id: 'c-%e9%ba%bb%e8%b1%86%e4%bc%a0%e5%aa%92', name: '麻豆传媒' },
  { id: 'c-%e9%ba%bb%e8%b1%86%e7%95%aa%e5%a4%96%e7%af%87', name: '麻豆番外篇' },
  { id: 'c-%e9%ba%bb%e8%b1%86%e8%8a%b1%e7%b5%ae', name: '麻豆花絮' },
  { id: 'c-%e6%9e%9c%e5%86%bb%e4%bc%a0%e5%aa%92', name: '果冻传媒' },
  { id: 'c-%e8%9c%9c%e6%a1%83%e5%bd%b1%e5%83%8f', name: '蜜桃影像' },
  { id: 'c-hongkongdoll', name: 'HongKongDoll' },
  { id: 'c-psychoporntw', name: 'PsychoPornTW' }
];

function clean(s) {
  return String(s == null ? '' : s)
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ')
    .replace(/&#x([0-9a-f]+);/gi, function (_m, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_m, d) { return String.fromCharCode(parseInt(d, 10)); })
    .trim();
}

async function req(path) {
  const url = path.indexOf('http') === 0 ? path : SITE + path;
  const res = await host.http.request({ url: url, headers: HEADERS, timeout: 25000 });
  if (res.status === 429) throw host.error('E_RATE_LIMIT', '站点限流了，等 1~2 分钟再试');
  if (res.status === 404) throw host.error('E_NOT_FOUND', '页面不存在');
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return typeof res.body === 'string' ? res.body : '';
}

// 卡片：<article class="excerpt …">
//   <a class="thumbnail" href="https://madou.club/{slug}.html">
//     <img data-src="https://madou.club/covers/…-240x180.jpg" class="thumb"></a>
//   <h2><a href="…{slug}.html">标题</a></h2>
function parseCards(html) {
  const out = [], seen = {};
  const re = /href="https:\/\/madou\.club\/([^"\/]+\.html)"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const id = m[1];
    if (seen[id]) continue;
    seen[id] = 1;
    const chunk = html.substring(m.index, m.index + 1600);
    const cov = chunk.match(/data-src="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp))"/i)
      || chunk.match(/<img[^>]+src="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp))"/i);
    const t2 = chunk.match(/<h2[^>]*>\s*<a[^>]*>([\s\S]*?)<\/a>/);
    const hot = chunk.match(/<time[^>]*class="[^"]*hot[^"]*"[^>]*>([^<]*)</);
    out.push({
      id: id,
      title: t2 ? clean(t2[1]) : decodeURIComponent(id.replace(/\.html$/, '')),
      cover: cov ? cov[1] : '',
      remarks: hot ? clean(hot[1]) : ''
    });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 14 };
}

function listPath(kind, page) {
  if (kind === 'likes') return '/likes';
  if (kind.indexOf('c-') === 0) {
    const slug = kind.slice(2);
    return '/category/' + slug + '/' + (page > 1 ? 'page/' + page + '/' : '');
  }
  return '/' + (page > 1 ? 'page/' + page + '/' : '');
}

function detailPath(id) {
  const p = String(id == null ? '' : id).replace(/^\//, '');
  if (/^[^\/]+\.html$/.test(p)) return '/' + p;
  return '';
}

export default {
  async home() {
    const html = await req('/');
    return { categories: CATEGORIES, items: parseCards(html), hasMore: true };
  },

  async category(input) {
    const id = String(input.id || 'latest');
    const page = Math.max(1, Number(input.page || 1));
    const kind = CATEGORIES.some(function (c) { return c.id === id; }) ? id : 'latest';
    const html = await req(listPath(kind, page));
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Math.max(1, Number(input.page || 1));
    if (!kw) return { items: [], page: 1, hasMore: false };
    const path = (page > 1 ? '/page/' + page + '/' : '/') + '?s=' + encodeURIComponent(kw);
    const html = await req(path);
    return pageResult(parseCards(html), page);
  },

  async detail(input) {
    const path = detailPath(input.id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await req(path);

    const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/);
    let title = h1 ? clean(h1[1]) : '';
    if (!title) title = decodeURIComponent(String(input.id).replace(/\.html$/, ''));
    const og = html.match(/property="og:image"[^>]*content="([^"]+)"/) || html.match(/content="([^"]+)"[^>]*property="og:image"/);
    const emb = html.match(/<iframe[^>]+src=["']?([^"'\s>]+)/);

    // 详情页本身没有特色图（og:image 为空），封面在 share 页 DPlayer 的 pic 里
    let cover = '';
    if (emb) {
      let share = emb[1];
      if (share.indexOf('//') === 0) share = 'https:' + share;
      try {
        const sp = await req(share);
        const pic = sp.match(/pic:\s*'([^']+)'/) || sp.match(/pic:\s*"([^"]+)"/);
        if (pic) cover = pic[1].indexOf('http') === 0 ? pic[1] : DASH + pic[1];
      } catch (e) { cover = ''; }
    }

    return {
      title: title,
      cover: cover,
      desc: emb ? '播放器：dash.madou.club' : '⚠️ 页面上没有播放器',
      lines: [{ name: '默认', episodes: [{ id: String(input.id), name: title }] }]
    };
  },

  async play(input) {
    const path = detailPath(input.episodeId || input.id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');

    // 1) 详情页里的 iframe → share 页
    const html = await req(path);
    const ifr = html.match(/<iframe[^>]+src=["']?([^"'\s>]+)/);
    if (!ifr) throw host.error('E_UNSUPPORTED', '详情页里没有播放器');
    let share = ifr[1];
    if (share.indexOf('//') === 0) share = 'https:' + share;

    // 2) share 页里现取 token 和 m3u8 路径（token 只有 100 秒有效期）
    const page = await req(share);
    const tok = page.match(/var token\s*=\s*"([^"]*)"/);
    const m3 = page.match(/var m3u8\s*=\s*'([^']*)'/) || page.match(/var m3u8\s*=\s*"([^"]*)"/);
    if (!m3) throw host.error('E_UNSUPPORTED', '播放器里没有找到 m3u8 地址');

    let url = m3[1].indexOf('http') === 0 ? m3[1] : DASH + m3[1];
    if (tok && tok[1] && url.indexOf('token=') < 0) url += (url.indexOf('?') >= 0 ? '&' : '?') + 'token=' + tok[1];

    host.log('play ' + String(input.episodeId || input.id) + ' → 1 条线路 (hls)');
    host.log('  ' + url.slice(0, 120));
    return {
      streams: [{
        name: '线路1',
        url: url,
        format: 'hls',
        headers: { 'Referer': SITE + '/', 'User-Agent': UA }
      }]
    };
  }
};
