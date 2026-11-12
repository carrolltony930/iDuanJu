// 99vv94.com（久久热）· i短剧 idj1 插件
//
// 站点是 KVS 内核（kt_player），但**游客能直接拿到 MP4 直链**：
//   详情页 flashvars = { video_url: 'https://99vv94.com/get_file/3/{hash}/{id}/{file}.mp4/', … }
//   没有「游客试看已达上限」这类登录墙（这点和 91rb 不同）。
//
//   列表   /latest-updates/{page}/ · /top-rated/{page}/ · /categories/{hash}/{page}/
//   详情   /videos/{id}/{slug}/
//   搜索   /search/?q=…

const SITE = 'https://99vv94.com';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const HEADERS = {
  'User-Agent': UA,
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9',
  'Referer': SITE + '/'
};

// 分类 id 用 c- 前缀和列表 id 区分
// 分类 hash 是 32 位，少一位站点也会回 200 但内容是错的——必须用完整值
const CATEGORIES = [
  { id: 'latest-updates', name: '最新的' },
  { id: 'top-rated', name: '最受欢迎' },
  { id: 'c-cb186ba8ba160b86eb97025b94353cac', name: '高清' },
  { id: 'c-ffc7a71b8aae8ddd42031cea42f5bb7c', name: '欧美' },
  { id: 'c-2f3d48fda20378cbdaaa099069a4af1e', name: '日本有码' },
  { id: 'c-281b22d3c2f5255ed57e7a09bee253f1', name: '韩国女主播' },
  { id: 'c-5a4e793520c46cbaee2b67760e5aea28', name: '东京热' },
  { id: 'c-f8789a0bf37c07c754c8e6fe27daf5f6', name: '成人动漫' },
  { id: 'c-251e96ab3240ae3fb3dc8bc854061957', name: '会员认证作品' },
  { id: 'c-b8e26987b0bae1d56a00de631e4d1a68', name: '李宗瑞全集' },
  { id: 'c-0c3d3255db00e2d3b27db08661096b23', name: '肛交' }
];

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
//   <div class="item"><a href="https://99vv94.com/videos/228985/11148/" title="标题">
//     <div class="img"><img class="thumb" src="/contents/videos_screenshots/…/1.jpg" alt="标题">
function parseCards(html) {
  const out = [], seen = {};
  const re = /href="https:\/\/99vv94\.com\/videos\/(\d+)\/([^"\/]+)\/"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const id = 'videos/' + m[1] + '/' + m[2];
    if (seen[id]) continue;
    seen[id] = 1;
    const chunk = html.substring(m.index, m.index + 900);
    const cov = chunk.match(/<img[^>]+src="([^"]+\.(?:jpg|jpeg|png|webp))"/i);
    const title = chunk.match(/title="([^"]+)"/) || chunk.match(/alt="([^"]+)"/);
    const dur = chunk.match(/class="duration"[^>]*>([\s\S]*?)</);
    let cover = cov ? cov[1] : '';
    if (cover.indexOf('//') === 0) cover = 'https:' + cover;
    else if (cover.indexOf('/') === 0) cover = SITE + cover;
    out.push({
      id: id,
      title: title ? clean(title[1]) : m[1],
      cover: cover,
      remarks: dur ? clean(dur[1]) : ''
    });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 20 };
}

function categoryPath(id, page) {
  // 分类页必须带尾部斜杠：
  //   /categories/{hash}/      → 分类列表（不带斜杠会命中站点的兜底页，固定 92060 字节）
  //   /categories/{hash}/{n}/  → 第 n 页
  if (id.indexOf('c-') === 0) {
    const h = id.slice(2);
    return '/categories/' + h + '/' + (page > 1 ? page + '/' : '');
  }
  return '/' + id + '/' + (page > 1 ? page + '/' : '');
}

function detailPath(id) {
  const p = String(id == null ? '' : id).replace(/^\//, '').replace(/\/$/, '');
  if (/^videos\/\d+\/[^\/]+$/.test(p)) return '/' + p + '/';
  return '';
}

export default {
  async home() {
    const html = await req('/latest-updates/');
    return { categories: CATEGORIES, items: parseCards(html), hasMore: true, coverHeaders: { 'Referer': SITE + '/', 'User-Agent': UA } };
  },

  async category(input) {
    const id = String(input.id || 'latest-updates');
    const page = Math.max(1, Number(input.page || 1));
    const kind = CATEGORIES.some(function (c) { return c.id === id; }) ? id : 'latest-updates';
    const html = await req(categoryPath(kind, page));
    const r = pageResult(parseCards(html), page); r.coverHeaders = { 'Referer': SITE + '/', 'User-Agent': UA }; return r;
  },

  async search(input) {
    const kw = clean(input.keyword || '');
    const page = Math.max(1, Number(input.page || 1));
    if (!kw) return { items: [], page: 1, hasMore: false };
    const html = await req('/search/?q=' + encodeURIComponent(kw) + (page > 1 ? '&page=' + page : ''));
    const r = pageResult(parseCards(html), page); r.coverHeaders = { 'Referer': SITE + '/', 'User-Agent': UA }; return r;
  },

  async detail(input) {
    const path = detailPath(input.id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await req(path);

    const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/);
    let title = h1 ? clean(h1[1]) : '';
    const og = html.match(/property="og:image"[^>]*content="([^"]+)"/);
    let cover = og ? og[1] : '';
    if (cover.indexOf('/') === 0) cover = SITE + cover;
    if (cover.indexOf('//') === 0) cover = 'https:' + cover;

    const src = html.match(/video_url:\s*'([^']+)'/) || html.match(/video_url:\s*"([^"]+)"/);
    const altSrc = html.match(/video_alt_url:\s*'([^']+)'/);
    if (src) srcCache[String(input.id)] = { u: src[1], t: Date.now() };
    const tags = html.match(/video_tags:\s*'([^']*)'/);
    if (!title) title = String(input.id).split('/').pop();
    const noSource = !(src && src[1]) && !(altSrc && /^https?:/.test(altSrc[1]));

    return {
      title: title,
      cover: cover,
      desc: (tags ? '标签：' + clean(tags[1]) : '') + (noSource ? ' ⚠️ 该视频没有免费片源' : ''),
      coverHeaders: { 'Referer': SITE + '/', 'User-Agent': UA },
      lines: [{ name: '默认', episodes: [{ id: String(input.id), name: title }] }]
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
      const m = html.match(/video_url:\s*'([^']+)'/) || html.match(/video_url:\s*"([^"]+)"/);
      if (m) {
        src = m[1];
        srcCache[id] = { u: src, t: Date.now() };
      } else {
        // 有些条目没有 video_url（付费/下架），flashvars 里可能还有 video_alt_url
        const alt = html.match(/video_alt_url:\s*'([^']+)'/);
        if (alt && /^https?:/.test(alt[1])) src = alt[1];
      }
      if (!src) throw host.error('E_UNSUPPORTED', '这个视频没有免费片源（页面里没有 video_url，可能需要会员）');
    }
    if (src.indexOf('//') === 0) src = 'https:' + src;

    const isMp4 = /\.mp4(\/|\?|$)/i.test(src);
    host.log('play ' + id + ' → 1 条线路 (' + (isMp4 ? 'mp4' : 'hls') + ')');
    host.log('  ' + src);
    return {
      streams: [{
        name: '线路1',
        url: src,
        format: isMp4 ? 'mp4' : 'hls',
        headers: { 'Referer': SITE + '/', 'User-Agent': UA }
      }]
    };
  }
};
