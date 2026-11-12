// 7mmtv.sx（7mm 線上AV）· i短剧 idj1 插件
//
// 站点是繁體中文路径式分页：
//   列表 /zh/{cat}_list/all/{page}.html
//   详情 /zh/{cat}_content/{vid}/{code}.html
//   搜索 POST /zh/searchform_search/all/index.html （search_keyword / search_type / op）
//
// 详情页本身没有播放地址，只有 JSON-LD 里的 contentUrl 指向 VidHide 的 embed 页
// （mmvh02.com/v/xxxx）。embed 页里是一段 Dean Edwards packer，解开后 links.hls2 就是
// 带 token 的 master.m3u8（480p/720p/1080p 三档），hls3 是同一份内容的 .txt 备用源。
// 解包用宿主的 host.codec.unpackPacker，不会执行远程 JS。

const SITE = 'https://7mmtv.sx';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9',
  'Referer': SITE + '/'
};

// 路径前缀与详情路径前缀一一对应：{id}_list / {id}_content
const CATEGORIES = [
  { id: 'censored', name: '有碼 AV' },
  { id: 'chinese', name: '中字 AV' },
  { id: 'uncensored', name: '無碼 AV' },
  { id: 'amateurjav', name: '素人 AV' },
  { id: 'amateur', name: '國產影片' },
  { id: 'reducing-mosaic', name: '薄碼' },
  { id: 'klive', name: '韓國直播' },
  { id: 'clive', name: '中國直播' }
];

// 详情页里的 embed 地址缓存，play() 不必再拉一次详情
const embCache = {};

function clean(s) {
  return String(s == null ? '' : s)
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ')
    .replace(/&#x([0-9a-f]+);/gi, function (_m, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_m, d) { return String.fromCharCode(parseInt(d, 10)); })
    .trim();
}

async function req(url, opts) {
  const o = { url: url, headers: HEADERS, timeout: 25000 };
  if (opts) for (const k in opts) o[k] = opts[k];
  const res = await host.http.request(o);
  if (res.status === 403) throw host.error('E_RATE_LIMIT', '站点限流了，等 1~2 分钟再试');
  if (res.status === 404) throw host.error('E_NOT_FOUND', '页面不存在');
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + url);
  return typeof res.body === 'string' ? res.body : '';
}

// 卡片：<a href="https://7mmtv.sx/zh/censored_content/207841/HMRK-047.html">
//         <img class='lazyload' data-src='https://n3.1026cdn.sx/censored/s/442677_HMRK-047.jpg' alt='标题'>
//         <video … data-src='预览.mp4'>   ← 卡片上的 mp4 是几秒预览，不是正片
function parseCards(html) {
  const out = [], seen = {};
  const re = /href="https:\/\/7mmtv\.sx\/zh\/([a-z\-]+)_content\/(\d+)\/([^"\/]+)\.html"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const cat = m[1], vid = m[2], code = m[3];
    const id = cat + '/' + vid + '/' + code;
    if (seen[id]) continue;
    seen[id] = 1;
    const chunk = html.substring(m.index, m.index + 900);
    const img = chunk.match(/data-src=['"]([^'"]+\.(?:jpg|jpeg|png|webp))['"]/i);
    const alt = chunk.match(/alt=['"]([^'"]*)['"]/);
    let title = alt ? clean(alt[1]) : '';
    if (!title) title = code;
    out.push({ id: id, title: title, cover: img ? img[1] : '', remarks: code });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 20 };
}

function detailPath(id) {
  const p = String(id == null ? '' : id).split('/');
  if (p.length >= 3 && p[0] && p[1] && p[2]) {
    return '/zh/' + p[0] + '_content/' + p[1] + '/' + p[2] + '.html';
  }
  return '';
}

// 从 master 里挑一档「高度不超过 720」的最高变体，直接给媒体播放列表：
// 1080p 要 2.8Mbps，而这一类 CDN 实测只有 ~3.5Mbps，预缓冲和播放抢带宽就会卡；
// 720p（1.47Mbps）留出两倍余量，起播和拖动都稳。master 仍然作为第二条线路给需要的人。
async function bestVariant(masterUrl) {
  try {
    const txt = await req(masterUrl);
    const lines = txt.split(/\r?\n/);
    let pick = '';
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i].trim();
      if (l.indexOf('#EXT-X-STREAM-INF') !== 0) continue;
      const res = l.match(/RESOLUTION=(\d+)x(\d+)/);
      const h = res ? Number(res[2]) : 0;
      const uri = (lines[i + 1] || '').trim();
      if (!uri || uri.charAt(0) === '#') continue;
      if (h && h <= 720 && uri) pick = uri;
    }
    if (!pick) return '';
    return pick.indexOf('http') === 0 ? pick : masterUrl.replace(/[^\/]*$/, '') + pick;
  } catch (e) {
    return '';
  }
}
function pickStreams(text) {
  const found = [];
  const re = /"(hls\d?)"\s*:\s*"(https?:\/\/[^"]+)"/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const key = m[1], url = m[2].replace(/\\\//g, '/');
    if (!/^https?:\/\//.test(url)) continue;
    if (!/\.(m3u8|txt)(\?|$)/.test(url)) continue;
    let dup = false;
    for (const f of found) if (f.url === url) dup = true;
    if (!dup) found.push({ key: key, url: url });
  }
  // hls2 是标准 m3u8，hls3 是 .txt 备用，hls4 不常见
  const rank = { hls2: 0, hls3: 1, hls4: 2 };
  found.sort(function (a, b) {
    return (rank[a.key] === undefined ? 9 : rank[a.key]) - (rank[b.key] === undefined ? 9 : rank[b.key]);
  });
  return found;
}

// Dean Edwards packer 的自实现：宿主的 unpackPacker 用 `([^']*)` 取字典，
// 遇到字典里含单引号的页面（7mmtv 常见）就会失败，所以这里按转义扫描来取字符串。
function readQuoted(s, i) {
  if (s.charAt(i) !== "'") return null;
  let out = '', j = i + 1;
  while (j < s.length) {
    const ch = s.charAt(j);
    if (ch === '\\') { out += s.charAt(j + 1); j += 2; continue; }
    if (ch === "'") return [out, j + 1];
    out += ch; j += 1;
  }
  return null;
}

function unpackPacker(html) {
  const start = html.indexOf('eval(function(p,a,c,k,e');
  if (start < 0) return '';
  const open = html.indexOf('}(', start);
  if (open < 0) return '';
  const pq = readQuoted(html, open + 2);
  if (!pq) return '';
  const rest = html.slice(pq[1]);
  const nums = /^\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*/.exec(rest);
  if (!nums) return '';
  const base = Number(nums[1]), count = Number(nums[2]);
  const dq = readQuoted(html, pq[1] + nums[0].length);
  if (!dq) return '';
  const dict = dq[0].split('|');
  let text = pq[0];
  const D = '0123456789abcdefghijklmnopqrstuvwxyz';
  function toBase(n, b) {
    if (n === 0) return '0';
    let s = '';
    while (n > 0) { s = D.charAt(n % b) + s; n = Math.floor(n / b); }
    return s;
  }
  for (let k = count - 1; k >= 0; k--) {
    if (k < dict.length && dict[k]) {
      const re = new RegExp('\\b' + toBase(k, base) + '\\b', 'g');
      text = text.split(re).join(dict[k]);
    }
  }
  return text;
}
export default {
  async home() {
    const html = await req(SITE + '/zh/');
    return { categories: CATEGORIES, items: parseCards(html), hasMore: true };
  },

  async category(input) {
    const id = String(input.id || 'censored');
    const page = Math.max(1, Number(input.page || 1));
    const kind = CATEGORIES.some(function (c) { return c.id === id; }) ? id : 'censored';
    const html = await req(SITE + '/zh/' + kind + '_list/all/' + page + '.html');
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Math.max(1, Number(input.page || 1));
    if (!kw) return { items: [], page: 1, hasMore: false };
    const html = await req(SITE + '/zh/searchform_search/all/index.html', {
      method: 'POST',
      body: { search_keyword: kw, search_type: 'searchall', op: 'search' },
      bodyType: 'form'
    });
    return pageResult(parseCards(html), page);
  },

  async detail(input) {
    const path = detailPath(input.id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await req(SITE + path);

    const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/);
    let title = h1 ? clean(h1[1]) : '';
    if (!title) {
      const ld = html.match(/"name"\s*:\s*"([^"]+)"/);
      title = ld ? clean(ld[1]) : '';
    }
    if (!title) title = String(input.id).split('/').pop();

    const cover = html.match(/"image"\s*:\s*"([^"]+)"/);
    const desc = html.match(/"description"\s*:\s*"([^"]*)"/);
    const emb = html.match(/"contentUrl"\s*:\s*"([^"]+)"/);
    if (emb) embCache[String(input.id)] = { u: emb[1], t: Date.now() };

    return {
      title: title,
      cover: cover ? cover[1].replace(/\\\//g, '/') : '',
      desc: desc ? clean(desc[1]).substring(0, 180) : '',
      lines: [{ name: '默认', episodes: [{ id: String(input.id), name: title || '播放' }] }]
    };
  },

  async play(input) {
    const id = String(input.episodeId || input.id || '');
    const path = detailPath(id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');

    // 1) 详情页 → embed 地址（mmvh02.com/v/xxxx）
    let embed = '';
    const c = embCache[id];
    if (c && Date.now() - c.t < 60 * 60 * 1000) embed = c.u;
    if (!embed) {
      const html = await req(SITE + path);
      const m = html.match(/"contentUrl"\s*:\s*"([^"]+)"/);
      if (!m) throw host.error('E_UNSUPPORTED', '这个页面拿不到播放器地址（國產影片分类暂不支持）');
      embed = m[1];
      embCache[id] = { u: embed, t: Date.now() };
    }

    // 2) 按域名分流：DoodStream 系 embed（playmogo / mmsi / dood）的 Turnstile 要点「播放」才出现，
    //    宿主的自动过验证流程看不到它，所以先请 App 弹一次「去验证」：用户在插件专属 webview 里
    //    点播放、过人机验证，cookie 存进插件 jar，回来重试就能取到直链。
    const embHost = (String(embed).match(/^https?:\/\/([^\/]+)/) || [])[1] || '';
    const isDood = /(playmogo|mmsi\d*|dood|ds2play|d000d|doodstream)/i.test(embHost);

    // 3) 取 embed 页（宿主遇到 Cloudflare 挑战会自动后台验证）
    const page = await req(embed, { cloudflare: 'auto' });

    if (isDood) {
      const direct = page.match(/https?:\/\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*/i);
      if (direct) {
        const u = direct[0].replace(/\\\//g, '/');
        host.log('play ' + id + ' → DoodStream 直链');
        host.log('  ' + u);
        return {
          streams: [{
            name: '线路1',
            url: u,
            format: /\.m3u8/i.test(u) ? 'hls' : 'mp4',
            headers: { 'Referer': embed, 'User-Agent': UA }
          }]
        };
      }
      const key = 'verify:' + id;
      const tried = host.storage.get(key);
      if (!tried) {
        host.storage.set(key, 1);
        throw host.error('E_VERIFY', embed);
      }
      throw host.error('E_UNSUPPORTED', '人机验证后仍拿不到地址（该视频可能已失效）');
    }
    let text = '';
    try { text = unpackPacker(page); } catch (e) { text = ''; }
    if (!text) { try { text = String(host.codec.unpackPacker(page) || ''); } catch (e) { text = ''; } }
    let list = pickStreams(text);
    if (!list.length) list = pickStreams(page);
    if (!list.length) {
      const dood = /turnstile|g-recaptcha|DoodStream/i.test(page);
      throw host.error('E_UNSUPPORTED', dood
        ? '这个视频需要人机验证（' + embHost + '），App 版本支持后可点「去验证」'
        : '播放器配置解析失败（embed 域名 ' + embHost + '，页面 ' + page.length + ' 字节）');
    }

    const hdr = { 'Referer': SITE + '/', 'User-Agent': UA };
    const streams = [];
    // 线路1 直接给 720p 媒体列表（避开 1080p 抢带宽），线路2 才是 master
    const master = list[0] ? list[0].url : '';
    if (master && /\.m3u8(\?|$)/.test(master)) {
      const v = await bestVariant(master);
      if (v) streams.push({ name: '720p', url: v, format: 'hls', headers: hdr });
    }
    const names = { hls2: '线路1', hls3: '线路2', hls4: '线路3' };
    for (const it of list) {
      streams.push({
        name: names[it.key] || '线路' + (streams.length + 1),
        url: it.url,
        format: 'hls',
        headers: hdr
      });
    }
    host.log('play ' + id + ' → ' + streams.length + ' 条线路');
    for (const s of streams) host.log('  ' + s.name + ' ' + s.url);
    return { streams: streams };
  }
};
