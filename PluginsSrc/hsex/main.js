// 好色™ Tv（hsex.icu）· i短剧 idj1 插件
//
// 传统 PHP 站点，分页全是「路径式」：list-2.htm / search-2.htm / top7_list-2.htm。
// 详情页的播放地址直接写在 <source id="video-source" src="…index.m3u8"> 里，
// 但 CDN 有多条线路（站点 JS 里的 cdnNodes），同一路径换 host 即可——这里全部返回，
// 播放器里可以手动切换「播放源」。

const SITE = 'https://hsex.icu';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  'Referer': SITE + '/'
};

// 与站点 fastest_cdn.js 里的 cdnNodes 一致
const CDN_HOSTS = [
  { name: '线路1', host: 'cdn.hdcdn.store' },
  { name: '线路2 Shark', host: 'shark.hdcdn.store' },
  { name: '线路3 FDC', host: 'fdc.hdcdn.store' },
  { name: '线路4 IPv6', host: 'shark-v6.hdcdn.store' }
];

// detail() 抓到的播放地址缓存，play() 直接复用，避免重复请求详情页被 429
const srcCache = {};

const CATEGORIES = [
  { id: 'list', name: '最新' },
  { id: 'top7_list', name: '周榜' },
  { id: 'top_list', name: '月榜' },
  { id: '5min_list', name: '5分钟+' },
  { id: 'long_list', name: '10分钟+' }
];

function clean(s) {  return String(s == null ? '' : s)
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ')
    .replace(/&#x([0-9a-f]+);/gi, function (_m, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_m, d) { return String.fromCharCode(parseInt(d, 10)); })
    .trim();
}

async function getHtml(path) {
  const res = await host.http.request({ url: SITE + path, headers: HEADERS, timeout: 25000 });
  const body = typeof res.body === 'string' ? res.body : '';
  if (res.status === 429) throw host.error('E_RATE_LIMIT', '站点限流了，等 1~2 分钟再试');
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return body;
}

// 卡片：<a href="video-ID.htm"><div class="image" style="background-image:url('封面')" title="标题">
//        …<var class="duration">16:12</var>…</div></a>
//        <div class="caption title"><h5><a href="video-ID.htm">标题</a></h5></div>
function parseCards(html) {
  const map = {};
  const order = [];

  function touch(id) {
    if (!map[id]) {
      // 缩略图路径是固定规律，直接按 id 构造，不依赖 HTML 属性（站点的 title= 后面有空格，正则易碎）
      map[id] = { id: id, title: '', cover: 'https://i.hdcdn.store/thumb/' + id + '.webp', remarks: '' };
      order.push(id);
    }
    return map[id];
  }

  // 1) 封面（HTML 里若有就以它为准）+ 标题属性
  const reImg = /href="video-(\d+)\.htm"[\s\S]{0,400}?background-image:\s*url\('([^']+)'\)[\s\S]{0,80}?title\s*=\s*"([^"]*)"/g;
  let m;
  while ((m = reImg.exec(html)) !== null) {
    const it = touch(m[1]);
    if (m[2]) it.cover = m[2];
    if (!it.title) it.title = clean(m[3]);
  }

  // 2) 时长
  const reDur = /href="video-(\d+)\.htm"[\s\S]{0,500}?<var class="duration">([^<]+)<\/var>/g;
  while ((m = reDur.exec(html)) !== null) {
    const it = touch(m[1]);
    if (!it.remarks) it.remarks = clean(m[2]);
  }

  // 3) 标题（h5 里的链接文字），补上第 1 步没抓到 title 属性的
  const reH5 = /<h5>\s*<a[^>]+href="video-(\d+)\.htm"[^>]*>([\s\S]*?)<\/a>/g;
  while ((m = reH5.exec(html)) !== null) {
    const it = touch(m[1]);
    const t = clean(m[2]);
    if (t && (!it.title || it.title.length < t.length)) it.title = t;
  }

  // 4) 兜底：只有 id 的也保留（标题用 id）
  const reAny = /href="video-(\d+)\.htm"/g;
  while ((m = reAny.exec(html)) !== null) touch(m[1]);

  const out = [];
  const seen = {};
  for (const id of order) {
    const it = map[id];
    if (seen[id]) continue;
    seen[id] = 1;
    if (!it.title) it.title = '视频 ' + id;
    out.push(it);
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 20 };
}

function listPath(kind, page) {
  const n = Number(page || 1) < 1 ? 1 : Number(page || 1);
  return '/' + kind + '-' + n + '.htm';
}

function videoId(v) {
  const s = String(v == null ? '' : v);
  const m = s.match(/(\d{4,})/);
  return m ? m[1] : '';
}

export default {
  async home() {
    const html = await getHtml(listPath('list', 1));
    return { categories: CATEGORIES, items: parseCards(html), hasMore: true };
  },

  async category(input) {
    const id = String(input.id || 'list');
    const page = Number(input.page || 1);
    const kind = CATEGORIES.some(function (c) { return c.id === id; }) ? id : 'list';
    const html = await getHtml(listPath(kind, page));
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Number(input.page || 1);
    if (!kw) return { items: [], page: 1, hasMore: false };
    const n = page < 1 ? 1 : page;
    const html = await getHtml('/search-' + n + '.htm?search=' + encodeURIComponent(kw) + '&sort=new');
    return pageResult(parseCards(html), n);
  },

  async detail(input) {
    const id = videoId(input.id);
    if (!id) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await getHtml('/video-' + id + '.htm');
    const src = html.match(/id="video-source"[^>]*src="([^"]+)"/);
    if (src) srcCache[id] = { u: src[1], t: Date.now() };   // 顺手缓存，play() 就不用再拉一次（站点限流很凶）
    const t = html.match(/<h3 class="panel-title">([\s\S]*?)<\/h3>/);
    let title = t ? clean(t[1]) : '';
    if (!title) {
      const tt = html.match(/<title[^>]*>([\s\S]*?)<\/title>/);
      title = tt ? clean(tt[1]).replace(/\s*-\s*好色[^-]*$/, '') : id;
    }
    title = title.replace(/^\[原创\]\s*/, '').trim();
    const poster = html.match(/<video[^>]*poster="([^"]+)"/);
    return {
      title: title || id,
      cover: poster ? poster[1] : 'https://i.hdcdn.store/thumb/' + id + '.webp',
      lines: [{ name: '默认', episodes: [{ id: id, name: title || '播放' }] }]
    };
  },

  async play(input) {
    const id = videoId(input.episodeId || input.id);
    if (!id) throw host.error('E_NOT_FOUND', '缺少视频 id');

    // 优先用 detail() 缓存的地址（60 分钟内有效），避免再请求详情页触发 429
    let original = '';
    const c = srcCache[id];
    if (c && Date.now() - c.t < 60 * 60 * 1000) original = c.u;
    if (!original) {
      const html = await getHtml('/video-' + id + '.htm');
      const src = html.match(/id="video-source"[^>]*src="([^"]+)"/) || html.match(/<source[^>]+src="([^"]+\.m3u8[^"]*)"/);
      if (!src) throw host.error('E_UNSUPPORTED', '页面里没有找到播放地址');
      original = src[1];
      srcCache[id] = { u: original, t: Date.now() };
    }

    // 沙箱里没有 URL 对象，手工拆出 path + query（new URL 会抛 "URL is not defined"）
    const m = original.match(/^(https?:\/\/[^\/]+)(\/[^?#]*)?(\?[^#]*)?/);
    if (!m) throw host.error('E_PARSE', '播放地址无法解析');
    const path = m[2] || '/', query = m[3] || '';

    // 实测：线路1（cdn）经常「m3u8 能取、分片却取不到」，线路2（Shark）才是能播的。
    // 为了减少请求数、并且只交出一条线路（避免 App 切流卡死），这里按偏好顺序逐条探，
    // 命中第一条就返回，不再把 4 条全给出去。
    const order = [CDN_HOSTS[1], CDN_HOSTS[0], CDN_HOSTS[2]];   // Shark → cdn → FDC
    const hdr = { 'Referer': SITE + '/', 'User-Agent': UA };

    async function tryHost(node) {
      const u = 'https://' + node.host + path + query;
      try {
        const r = await host.http.request({ url: u, headers: hdr, timeout: 2500 });
        if (!(r.status >= 200 && r.status < 400)) return null;
        const body = typeof r.body === 'string' ? r.body : '';
        let seg = '';
        const lines = body.split(/\r?\n/);
        for (let k = 0; k < lines.length; k++) {
          const s = lines[k].trim();
          if (!s || s.charAt(0) === '#') continue;
          seg = s;
          break;
        }
        if (seg) {
          const segUrl = seg.indexOf('http') === 0 ? seg : u.replace(/[^\/]*$/, '') + seg;
          const r2 = await host.http.request({ url: segUrl, headers: hdr, timeout: 2500 });
          if (!(r2.status >= 200 && r2.status < 400)) return null;
        }
        return { name: node.name, url: u };
      } catch (e) {
        return null;
      }
    }

    let picked = null;
    for (let i = 0; i < order.length; i++) {
      picked = await tryHost(order[i]);
      if (picked) break;
    }
    if (!picked) picked = { name: CDN_HOSTS[1].name, url: 'https://' + CDN_HOSTS[1].host + path + query };

    const streams = [{
      name: picked.name,
      url: picked.url,
      format: 'hls',
      headers: { 'Referer': SITE + '/', 'User-Agent': UA }
    }];
    host.log('play ' + id + ' → 1 条线路（' + picked.name + '）');
    host.log('  ' + picked.url);
    return { streams: streams };
  }
};
