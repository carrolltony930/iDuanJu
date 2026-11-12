// 91porny.com（九色 / 91PORNY）· i短剧 idj1 插件
//
// 全站中文，传统服务端渲染：
//   列表 /video?page=N（视频）、/vod?page=N（精品）、/videos?page=N（蝌蚪）
//   详情 /video/view/{hex} 或 /video/viewhd/{hex} 或 /vod/view/{short}
//   搜索 GET /search?keywords=…&page=N
//
// 详情页的播放地址直接写在 <video id="video-play" data-src="…index.m3u8"
// data-poster="…jpg"> 里，不用解 Packer、没有 iframe 播放器、没有人机验证。

const SITE = 'https://91porny.com';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9',
  'Referer': SITE + '/'
};

const CATEGORIES = [
  { id: 'video', name: '最新视频' },
  { id: 'vod', name: '精品' },
  { id: 'videos', name: '蝌蚪' }
];

// detail() 抓到的直链缓存，play() 复用，少一次请求
const srcCache = {};

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
  const res = await host.http.request({ url: path.indexOf('http') === 0 ? path : SITE + path, headers: HEADERS, timeout: 25000 });
  if (res.status === 429) throw host.error('E_RATE_LIMIT', '站点限流了，等 1~2 分钟再试');
  if (res.status === 404) throw host.error('E_NOT_FOUND', '页面不存在');
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return typeof res.body === 'string' ? res.body : '';
}

// 卡片：
//   <a class="display d-block" href="/video/viewhd/HEX"><img class="img" src="//int.ucloud165.xyz/thumb/ID.jpg">
//     <small class="layer">00:00:12</small>…
//   </a>
//   <a class="title …" href="/video/viewhd/HEX">[原创] 标题</a>
function parseCards(html) {
  const out = [], seen = {};
  const re = /href="(\/(?:video\/view(?:hd)?\/[0-9a-f]{8,}|vod\/view\/[\w-]+))"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const id = m[1].replace(/^\//, '');
    if (seen[id]) continue;
    seen[id] = 1;
    const chunk = html.substring(m.index, m.index + 1200);
    const cov = chunk.match(/<img[^>]+src="(\/\/[^"]+\.(?:jpg|jpeg|png|webp))"/i);
    const dur = chunk.match(/class="layer">([^<]+)</);
    const title = chunk.match(/class="title[^"]*"[^>]*href="[^"]*"[^>]*>([^<]+)</)
      || chunk.match(/class="title[^"]*"[^>]*>([^<]+)</);
    const views = chunk.match(/(\d+)\s*次播放/);
    let cover = cov ? cov[1] : '';
    if (cover.indexOf('//') === 0) cover = 'https:' + cover;
    out.push({
      id: id,
      title: title ? clean(title[1]) : id,
      cover: cover,
      remarks: [dur ? clean(dur[1]) : '', views ? views[1] + '次' : ''].filter(Boolean).join(' · ')
    });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 20 };
}

// 详情页里取直链 / 封面 / 标题
function parseDetail(html, id) {
  const v = html.match(/<video[^>]*\bdata-src="([^"]+)"/) || html.match(/<video[^>]*\bsrc="(https?:[^"]+\.m3u8[^"]*)"/);
  const poster = html.match(/<video[^>]*\bdata-poster="([^"]+)"/);
  const t = html.match(/<title[^>]*>([\s\S]*?)<\/title>/);
  let title = t ? clean(t[1]) : '';
  title = title.replace(/\s*-\s*91[^-]*$/, '').trim();
  const og = html.match(/property="og:image"[^>]*content="([^"]+)"/) || html.match(/content="([^"]+)"[^>]*property="og:image"/);
  const desc = html.match(/property="og:description"[^>]*content="([^"]*)"/);
  let cover = poster ? poster[1] : (og ? og[1] : '');
  if (cover.indexOf('//') === 0) cover = 'https:' + cover;
  // HTML 属性里的 & 是 &amp;，不解码会污染 m3u8 的 query（t=…&amp;m=…）
  const unescape = function (s) { return String(s || '').replace(/&amp;/g, '&').replace(/&#x2F;/g, '/'); };
  return { src: unescape(v ? v[1] : ''), cover: unescape(cover), title: title || id, desc: desc ? clean(desc[1]).substring(0, 160) : '' };
}

function detailPath(id) {
  const p = String(id == null ? '' : id).replace(/^\//, '');
  if (/^(video|vod)\/view(?:hd)?\/[\w-]+$/.test(p)) return '/' + p;
  return '';
}

export default {
  async home() {
    const html = await req('/video');
    return { categories: CATEGORIES, items: parseCards(html), hasMore: true };
  },

  async category(input) {
    const id = String(input.id || 'video');
    const page = Math.max(1, Number(input.page || 1));
    const kind = CATEGORIES.some(function (c) { return c.id === id; }) ? id : 'video';
    const html = await req('/' + kind + (page > 1 ? '?page=' + page : ''));
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Math.max(1, Number(input.page || 1));
    if (!kw) return { items: [], page: 1, hasMore: false };
    const html = await req('/search?keywords=' + encodeURIComponent(kw) + (page > 1 ? '&page=' + page : ''));
    return pageResult(parseCards(html), page);
  },

  async detail(input) {
    const path = detailPath(input.id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await req(path);
    const d = parseDetail(html, String(input.id));
    if (d.src) srcCache[String(input.id)] = { u: d.src, t: Date.now() };
    return {
      title: d.title,
      cover: d.cover,
      desc: d.desc,
      lines: [{ name: '默认', episodes: [{ id: String(input.id), name: d.title }] }]
    };
  },

  async play(input) {
    const id = String(input.episodeId || input.id || '');
    const path = detailPath(id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');

    let src = '';
    const c = srcCache[id];
    if (c && Date.now() - c.t < 2 * 60 * 60 * 1000) src = c.u;
    if (!src) {
      const html = await req(path);
      const d = parseDetail(html, id);
      if (!d.src) throw host.error('E_UNSUPPORTED', '详情页里没有找到播放地址');
      src = d.src;
      srcCache[id] = { u: src, t: Date.now() };
    }

    host.log('play ' + id + ' → 1 条线路');
    host.log('  ' + src);
    return {
      streams: [{
        name: '线路1',
        url: src,
        format: 'hls',
        headers: { 'Referer': SITE + '/', 'User-Agent': UA }
      }]
    };
  }
};
