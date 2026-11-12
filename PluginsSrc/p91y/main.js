// 91porny.com / jiuse.icu（九色 / 91PORNY）· i短剧 idj1 插件
//
// 同一套系统两个域名，内容与接口一致，互为主备（首次探测，选中的记进 storage）。
//
// 可播的三类内容：
//   首页推荐  /                       → /video/view/{hex}
//   精品      /vod                    → /vod/view/{id}          HLS
//   蝌蚪/欧美  /videos                 → /videos/view/{id}/{slug}/ MP4
//   全部视频  /video                  → /video/viewhd/{hex}     ⚠ 这一列表几乎全是 VIP，
//                                     详情页 data-src 固定是 //cdn2.jiuse2.cloud/hlsd/js10/index.m3u8
//                                     （TARGETDURATION=2 的试看片段），播不了，已在 play() 里明确拒绝。
//   搜索      /search?keywords=…&page=N

const DOMAINS = ['91porny.com', 'jiuse.icu'];
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

let SITE = 'https://' + DOMAINS[0];

function headers() {
  return {
    'User-Agent': UA,
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9',
    'Referer': SITE + '/'
  };
}

let sitePicked = false;
async function pickSite() {
  if (sitePicked) return;
  sitePicked = true;
  const saved = host.storage.get('site');
  if (saved && DOMAINS.indexOf(String(saved)) >= 0) {
    SITE = 'https://' + saved;
    return;
  }
  for (const d of DOMAINS) {
    try {
      const r = await host.http.request({ url: 'https://' + d + '/vod', headers: headers(), timeout: 10000 });
      if (r.status >= 200 && r.status < 400) {
        SITE = 'https://' + d;
        host.storage.set('site', d);
        return;
      }
    } catch (e) { /* 换下一个域名 */ }
  }
}

// 只保留免费板块：/video 那个「全部」列表整页都是 VIP 试看（data-src 恒为 hlsd/jsN），
// 直接不提供，免得列表里一堆点进去播不了的条目。
const CATEGORIES = [
  { id: 'home', name: '首页推荐' },
  { id: 'vod', name: '精品' },
  { id: 'videos', name: '蝌蚪/欧美' }
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

function unescape(s) {
  return String(s || '').replace(/&amp;/g, '&').replace(/&#x2F;/g, '/');
}

async function req(path) {
  const res = await host.http.request({ url: path.indexOf('http') === 0 ? path : SITE + path, headers: headers(), timeout: 25000 });
  if (res.status === 429) throw host.error('E_RATE_LIMIT', '站点限流了，等 1~2 分钟再试');
  if (res.status === 404) throw host.error('E_NOT_FOUND', '页面不存在');
  if (res.status < 200 || res.status >= 300) throw host.error('E_HTTP', 'HTTP ' + res.status + ' ' + path);
  return typeof res.body === 'string' ? res.body : '';
}

const CARD_RE = /href="(\/(?:video\/view(?:hd)?\/[0-9a-f]+|vod\/view\/[\w-]+|videos\/view\/\d+\/[^"\/]+\/?))"/g;

function parseCards(html) {
  const out = [], seen = {};
  CARD_RE.lastIndex = 0;
  let m;
  while ((m = CARD_RE.exec(html)) !== null) {
    const id = m[1].replace(/^\//, '').replace(/\/$/, '');
    if (seen[id]) continue;
    seen[id] = 1;
    const chunk = html.substring(m.index, m.index + 1200);
    // 两种卡片：/video 用 <img src>，/vod 用 background-image（CSS）
    const cov = chunk.match(/<img[^>]+src="(\/\/[^"]+\.(?:jpg|jpeg|png|webp))"/i)
      || chunk.match(/background-image:\s*url\('([^']+)'\)/i)
      || chunk.match(/<img[^>]+src="(https?:\/\/[^"]+\.(?:jpg|jpeg|png|webp))"/i);
    const dur = chunk.match(/class="layer">([^<]+)</);
    const title = chunk.match(/class="title[^"]*"[^>]*href="[^"]*"[^>]*>([^<]+)</)
      || chunk.match(/class="title[^"]*"[^>]*>([^<]+)</);
    const views = chunk.match(/(\d+)\s*次播放/);
    // 带 vip-layer 的卡片是 VIP 试看，直接跳过不显示
    if (/vip-layer/.test(chunk)) continue;
    const vip = false;
    let cover = cov ? cov[1] : '';
    if (cover.indexOf('//') === 0) cover = 'https:' + cover;
    const bits = [];
    if (vip) bits.push('VIP');
    if (dur) bits.push(clean(dur[1]));
    if (views) bits.push(views[1] + '次');
    out.push({
      id: id,
      title: title ? clean(title[1]) : id,
      cover: cover,
      remarks: bits.join(' · ')
    });
  }
  return out;
}

function pageResult(items, page) {
  return { items: items, page: page, hasMore: items.length >= 20 };
}

function detailPath(id) {
  const p = String(id == null ? '' : id).replace(/^\//, '').replace(/\/$/, '');
  if (/^video\/view(?:hd)?\/[0-9a-f]+$/.test(p)) return '/' + p;
  if (/^vod\/view\/[\w-]+$/.test(p)) return '/' + p;
  if (/^videos\/view\/\d+\/[^\/]+$/.test(p)) return '/' + p + '/';
  return '';
}

function parseDetail(html, id) {
  const v = html.match(/<video[^>]*\bdata-src="([^"]+)"/) || html.match(/<video[^>]*\bsrc="(https?:[^"]+\.(?:m3u8|mp4)[^"]*)"/);
  const poster = html.match(/<video[^>]*\bdata-poster="([^"]+)"/);
  const t = html.match(/<title[^>]*>([\s\S]*?)<\/title>/);
  let title = t ? clean(t[1]) : '';
  title = title.replace(/\s*-\s*91[^-]*$/, '').replace(/\s*-\s*蝌蚪窝.*$/, '').trim();
  const og = html.match(/property="og:image"[^>]*content="([^"]+)"/) || html.match(/content="([^"]+)"[^>]*property="og:image"/);
  const desc = html.match(/property="og:description"[^>]*content="([^"]*)"/);
  let cover = poster ? poster[1] : (og ? og[1] : '');
  if (cover.indexOf('//') === 0) cover = 'https:' + cover;
  let src = unescape(v ? v[1] : '');
  // 站点的 data-src 有时是协议相对地址「//cdn2.jiuse2.cloud/…」，不补 scheme 会被播放器丢弃
  if (src.indexOf('//') === 0) src = 'https:' + src;
  return { src: src, cover: unescape(cover), title: title || id, desc: desc ? clean(desc[1]).substring(0, 160) : '' };
}

// VIP 视频的 data-src 固定是 …/hlsd/jsN/index.m3u8（TARGETDURATION=2 的试看片段），不是完整片。
function isVIPPreview(src) {
  return /\/hlsd\/js\d+\//.test(String(src || ''));
}

function listPath(kind, page) {
  const n = page > 1 ? (kind === 'home' ? '/?page=' + page : '?page=' + page) : '';
  if (kind === 'home') return '/' + n;
  return '/' + kind + n;
}

export default {
  async home() {
    await pickSite();
    const html = await req('/vod');
    return { categories: CATEGORIES, items: parseCards(html), hasMore: true };
  },

  async category(input) {
    await pickSite();
    const id = String(input.id || 'vod');
    const page = Math.max(1, Number(input.page || 1));
    const kind = CATEGORIES.some(function (c) { return c.id === id; }) ? id : 'vod';
    const html = await req(listPath(kind, page));
    return pageResult(parseCards(html), page);
  },

  async search(input) {
    await pickSite();
    const kw = clean(input.keyword || '');
    const page = Math.max(1, Number(input.page || 1));
    if (!kw) return { items: [], page: 1, hasMore: false };
    const html = await req('/search?keywords=' + encodeURIComponent(kw) + (page > 1 ? '&page=' + page : ''));
    return pageResult(parseCards(html), page);
  },

  async detail(input) {
    await pickSite();
    const path = detailPath(input.id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');
    const html = await req(path);
    const d = parseDetail(html, String(input.id));
    if (d.src && !isVIPPreview(d.src)) srcCache[String(input.id)] = { u: d.src, t: Date.now() };
    const vip = isVIPPreview(d.src) || !d.src;
    return {
      title: d.title,
      cover: d.cover,
      desc: vip ? '⚠️ VIP 视频，只能试看（需要会员才能看完整版）' : d.desc,
      lines: [{ name: '默认', episodes: [{ id: String(input.id), name: vip ? '试看（VIP）' : d.title }] }]
    };
  },

  async play(input) {
    await pickSite();
    const id = String(input.episodeId || input.id || '');
    const path = detailPath(id);
    if (!path) throw host.error('E_NOT_FOUND', '缺少视频 id');

    let src = '';
    const c = srcCache[id];
    if (c && Date.now() - c.t < 2 * 60 * 60 * 1000) src = c.u;
    if (!src) {
      const html = await req(path);
      const d = parseDetail(html, id);
      src = d.src;
      if (src && !isVIPPreview(src)) srcCache[id] = { u: src, t: Date.now() };
    }
    if (!src) throw host.error('E_UNSUPPORTED', '详情页里没有找到播放地址');
    if (isVIPPreview(src)) {
      throw host.error('E_UNSUPPORTED', '这是 VIP 视频，只能试看几十秒，需要会员才能看完整版');
    }

    const isMp4 = /\.mp4(\?|$)/i.test(src);
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
