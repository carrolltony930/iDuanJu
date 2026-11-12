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
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return body;
}

// 列表卡片：<a href="...view_video.php?viewkey=XXX..."> … <img src=thumb> … <span class="video-title">标题</span> … </a>
function parseCards(html) {
  const out = [];
  const seen = {};
  // 列表入口有 view_video.php 和 view_video_hd.php 两种（高清分类用后者）
  const re = /<a[^>]+href="[^"]*view_video[a-z_]*\.php\?viewkey=([0-9a-f]{12,32})[^"]*"([\s\S]{0,1500}?)<\/a>/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const id = m[1];
    if (seen[id]) continue;
    const block = m[2];
    const t = block.match(/class="video-title[^"]*"[^>]*>([\s\S]*?)<\/span>/);
    const title = t ? clean(t[1]) : '';
    if (!title) continue;
    seen[id] = 1;
    const img = block.match(/<img[^>]+src="([^"]+)"/);
    const dur = block.match(/class="duration"[^>]*>([^<]+)</);
    out.push({
      id: id,
      title: title,
      cover: img ? img[1] : '',
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

// 详情页里解出真正的 <source src='...'>
function decodeSource(html) {
  const m = html.match(/strencode2\(\s*"([^"]+)"\s*\)/);
  if (!m) return '';
  let text = m[1];
  try { text = decodeURIComponent(text); } catch (e) { /* 保持原样 */ }
  const s = text.match(/src\s*=\s*['"]([^'"]+)['"]/);
  return s ? s[1] : '';
}

function detailMeta(html) {
  const t = html.match(/<title[^>]*>([\s\S]*?)<\/title>/);
  let title = t ? clean(t[1]) : '';
  title = title.replace(/\s*-\s*91porn\s*$/i, '').trim();
  const poster = html.match(/<video[^>]*poster="([^"]+)"/);
  const dur = html.match(/<span class="duration">([^<]+)<\/span>/);
  return { title: title, poster: poster ? poster[1] : '', duration: dur ? clean(dur[1]) : '' };
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
    const html = await getHtml('/view_video.php?viewkey=' + id[0]);
    const url = decodeSource(html);
    if (!url || url.indexOf('http') !== 0) throw host.error('E_UNSUPPORTED', '没有找到可播放的直链');
    return {
      streams: [{
        name: 'MP4',
        url: url,
        format: 'mp4'
      }]
    };
  }
};
