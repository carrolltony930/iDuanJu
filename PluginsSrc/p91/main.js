// 91porn · i短剧 idj1 插件
//
// 站点是传统 PHP 页面，结构很稳：
//   列表 /v.php?next=watch 或 /v.php?category=<code>&page=N   （24 条/页）
//   详情 /view_video.php?viewkey=<20位hex>
//   搜索 /search_result.php?search_id=<关键词>&search_type=search_videos
//        —— 第 2 页起必须用站点返回的 search_id 令牌（不是原始关键词）
//
// 播放地址不是明文：详情页里是一句 document.write(strencode2("..."))，
// 参数就是 URL 编码后的 <source src='...'>，解一下就能拿到 MP4 直链。

const SITE = 'https://www.91porn.com';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  'Referer': SITE + '/'
};

// 站点分类（(?category=) 代码，名称取自站点）
// detail() 解出来的播放地址缓存，play() 复用，减少详情页请求（站点会 429）
const srcCache = {};

const CATEGORIES = [
  { id: 'cat:hd', name: '高清' },
  { id: 'cat:rf', name: '最近加精' },
  { id: 'cat:ori', name: '91原创' },
  { id: 'cat:hot', name: '当前最热' },
  { id: 'cat:top', name: '本月最热' },
  { id: 'cat:tf', name: '本月收藏' },
  { id: 'cat:mf', name: '收藏最多' },
  { id: 'cat:long', name: '10分钟以上' },
  { id: 'cat:longer', name: '20分钟以上' },
  { id: 'cat:md', name: '本月讨论' }
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

async function getHtml(path) {
  const res = await host.http.request({ url: SITE + path, headers: HEADERS, timeout: 25000 });
  const body = typeof res.body === 'string' ? res.body : '';
  if (res.status === 403 && body.indexOf('Just a moment') >= 0) throw host.error('E_CF_REQUIRED', '站点要求验证');
  if (res.status === 429) throw host.error('E_RATE_LIMIT', '站点限流了，等 1~2 分钟再试');
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return body;
}

// 列表页同一个视频会出现多次（44 张卡只有 24 个 viewkey），
// 而且旧的 HTML 里 <a> 标签不保证闭合，按 </a> 切分会把「下一张卡的标题」配到「上一张卡的 id」，
// 结果就是点封面进去是别的视频。这里改成以 viewkey 为边界切区间，每张卡只在自己的区间里取字段。
function parseCards(html) {
  const marks = [];
  const reKey = /viewkey=([0-9a-f]{12,32})/g;
  let m;
  while ((m = reKey.exec(html)) !== null) marks.push({ id: m[1], at: m.index });

  const out = [];
  const seenThumb = {};
  const seenId = {};
  for (let i = 0; i < marks.length; i++) {
    const start = marks[i].at;
    const end = i + 1 < marks.length ? marks[i + 1].at : Math.min(html.length, start + 2000);
    const seg = html.slice(start, end);

    const t = seg.match(/class="video-title[^"]*"[^>]*>([\s\S]*?)<\/span>/);
    const title = t ? clean(t[1]) : '';
    if (!title) continue;

    const img = seg.match(/<img[^>]+src="([^"]+)"/);
    const cover = img ? img[1] : '';
    const thumbId = (cover.match(/\/thumb\/(\d+)\./) || [])[1] || '';

    // 同一张缩略图 = 同一个视频，只保留第一次出现的那张卡
    const dk = thumbId || marks[i].id;
    if (seenThumb[dk] || seenId[marks[i].id]) continue;
    seenThumb[dk] = 1;
    seenId[marks[i].id] = 1;

    const dur = seg.match(/class="duration"[^>]*>([^<]+)</);
    out.push({
      id: marks[i].id,
      title: title,
      cover: cover,
      remarks: dur ? clean(dur[1]) : ''
    });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 20 };
}

function listPath(id, page) {
  const n = Number(page || 1);
  const idx = id && id.indexOf('cat:') === 0;
  const base = idx ? '/v.php?category=' + id.slice(4) : '/v.php?next=watch';
  return n <= 1 ? base : base + '&page=' + n;
}


function detailMeta(html) {
  const t = html.match(/<title[^>]*>([\s\S]*?)<\/title>/);
  let title = t ? clean(t[1]) : '';
  title = title.replace(/\s*-\s*91porn\s*$/i, '').trim();
  const poster = html.match(/<video[^>]*poster="([^"]+)"/);
  const dur = html.match(/<span class="duration">([^<]+)<\/span>/);
  return { title: title, poster: poster ? poster[1] : '', duration: dur ? clean(dur[1]) : '' };
}

// 详情页里可能有多条源：正常的 <source>、被 <!-- --> 包住的备用源、以及 strencode2 编码的那条。
// 全部解出来，后面逐个探测。
function decodeSources(html) {
  const urls = [];
  function push(u) {
    if (u && u.indexOf('http') === 0 && urls.indexOf(u) < 0) urls.push(u);
  }
  const reEnc = /strencode2\(\s*"([^"]+)"\s*\)/g;
  let m;
  while ((m = reEnc.exec(html)) !== null) {
    let t = m[1];
    try { t = decodeURIComponent(t); } catch (e) { /* 保持原样 */ }
    const s = t.match(/src\s*=\s*['"]([^'"]+)['"]/);
    if (s) push(s[1]);
  }
  const reSrc = /<source[^>]+src="([^"]+\.(?:mp4|m3u8)[^"]*)"/g;
  while ((m = reSrc.exec(html)) !== null) push(m[1]);
  return urls;
}


export default {
  async home() {
    const html = await getHtml(listPath('', 1));
    return {
      categories: [{ id: 'watch', name: '首页最新' }].concat(CATEGORIES),
      items: parseCards(html),
      hasMore: true
    };
  },

  async category(input) {
    const id = String(input.id || 'watch');
    const page = Number(input.page || 1);
    const html = await getHtml(listPath(id, page));
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Number(input.page || 1);
    if (!kw) return { items: [], page: 1, hasMore: false };

    const key = 'sid:' + kw;
    let sid = '';
    try { sid = String(host.storage.get(key) || ''); } catch (e) { /* 无 storage */ }

    // 有令牌且不是第一页：直接用令牌翻页
    if (page > 1 && sid) {
      const h = await getHtml('/search_result.php?search_id=' + encodeURIComponent(sid) +
                              '&search_type=search_videos&page=' + page);
      const items = parseCards(h);
      if (items.length) return pageResult(items, page);
    }

    // 第一页（或令牌失效）：用关键词换令牌
    const h1 = await getHtml('/search_result.php?search_id=' + encodeURIComponent(kw) + '&search_type=search_videos');
    const m = h1.match(/search_id=([0-9A-Fa-f]{5,12})&/);
    if (m) { sid = m[1]; try { host.storage.set(key, sid); } catch (e) { /* ignore */ } }
    if (page <= 1) return pageResult(parseCards(h1), 1);

    const h2 = await getHtml('/search_result.php?search_id=' + encodeURIComponent(sid || kw) +
                             '&search_type=search_videos&page=' + page);
    return pageResult(parseCards(h2), page);
  },

  async detail(input) {
    const id = String(input.id || '').match(/[0-9a-f]{12,32}/);
    if (!id) throw host.error('E_NOT_FOUND', '缺少 viewkey');
    const html = await getHtml('/view_video.php?viewkey=' + id[0]);
    const meta = detailMeta(html);
    const srcs = decodeSources(html);
    if (srcs.length) srcCache[id[0]] = { u: srcs[0], t: Date.now() };
    return {
      title: meta.title || id[0],
      cover: meta.poster,
      desc: meta.duration ? '时长 ' + meta.duration : '',
      lines: [{ name: '默认', episodes: [{ id: id[0], name: meta.title || '播放' }] }]
    };
  },

  async play(input) {
    const id = String(input.episodeId || input.id || '').match(/[0-9a-f]{12,32}/);
    if (!id) throw host.error('E_NOT_FOUND', '缺少 viewkey');

    let candidates = [];
    const cached = srcCache[id[0]];
    if (cached && Date.now() - cached.t < 60 * 60 * 1000) candidates = [cached.u];
    if (!candidates.length) {
      const html2 = await getHtml('/view_video.php?viewkey=' + id[0]);
      candidates = decodeSources(html2);
      if (candidates.length) srcCache[id[0]] = { u: candidates[0], t: Date.now() };
    }
    if (!candidates.length) throw host.error('E_UNSUPPORTED', '页面里没有找到播放地址');

    // 站点的地址路径是 //mp43/...（双斜杠）。Safari 会容错，但 AVFoundation 不一定，
    // 所以同时给一条单斜杠版本作为备用线路，播放器里可以手动切换。
    const streams = [];
    const seen = {};
    function add(name, u) {
      if (!u || u.indexOf('https://') !== 0 || seen[u]) return;
      seen[u] = 1;
      streams.push({
        name: name,
        url: u,
        format: /\.m3u8(\?|$)/.test(u) ? 'hls' : 'mp4',
        headers: { 'Referer': SITE + '/' }
      });
    }
    for (let i = 0; i < candidates.length && i < 2; i++) {
      const u = candidates[i];
      add('线路' + (streams.length + 1), u);
      // 只对第一条第路径里的重复斜杠做一次归一化
      if (i === 0 && /\/\/[^/]/.test(u.replace(/^https:\/\//, ''))) {
        add('线路' + (streams.length + 1) + ' 单斜杠', u.replace(/^https:\/\/([^/]+)\/\//, 'https://$1/'));
      }
    }
    if (!streams.length) throw host.error('E_UNSUPPORTED', '没有可用的 https 播放地址');
    host.log('play ' + id[0] + ' → ' + streams.length + ' 条线路');
    for (const s of streams) host.log('  ' + s.name + ' ' + s.format + ' ' + s.url);
    return { streams: streams };
  }
};
