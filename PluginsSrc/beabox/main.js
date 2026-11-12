// 笔盒（beabox）· i短剧 idj1 插件
//
// 站点结构：一堆 *.top 域名镜像 + /api 下的 JSON 接口。
// 响应体是 {"code":200,"data":"<hex>"}，data 用 AES-128-CBC 加密：
//   key = 下面这把 16 字节（hex），IV = 密文前 16 字节，PKCS7。
// 播放地址是直链 HLS，不需要 Referer。

const API_HOSTS = [
  'bh4066.top', 'bh4254.top', 'bh4287.top', 'bh4362.top', 'bh4549.top',
  'bh5530.top', 'bh6246.top', 'bh6291.top', 'bh6429.top', 'bh6831.top'
];
const AES_KEY = 'f6322fa1c064370ea40c8cdc20649f8e';
const PAGE_SIZE = 20;

let currentHost = null;

// ---------- 小工具 ----------

function pickHeader(headers, name) {
  if (!headers) return '';
  const want = String(name).toLowerCase();
  for (const k of Object.keys(headers)) {
    if (String(k).toLowerCase() === want) return String(headers[k] == null ? '' : headers[k]);
  }
  return '';
}

function clean(s) {
  return String(s == null ? '' : s)
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .trim();
}

function toHex(data, enc) {
  const e = String(enc || 'hex').toLowerCase();
  if (e === 'hex') return String(data);
  if (e === 'base64') return host.codec.convert(String(data), 'base64', 'hex');
  // 原始二进制（latin1）先转 hex
  return host.codec.convert(String(data), 'latin1', 'hex');
}

// ---------- 解密 ----------

function decryptPayload(cipher, encHeader) {
  const parts = String(encHeader).split(':');
  const algo = clean(parts[0] || '').toUpperCase();
  const fmt = clean(parts[1] || 'bin').toLowerCase();
  if (algo !== 'AES-128-CBC') throw host.error('E_PARSE', '不认识的加密方式：' + encHeader);

  const hex = toHex(cipher, fmt === 'hex' ? 'hex' : fmt);
  if (hex.length < 64) throw host.error('E_PARSE', '密文太短');
  const ivHex = hex.slice(0, 32);
  const bodyHex = hex.slice(32);

  const text = host.crypto.aes({
    op: 'decrypt',
    mode: 'cbc',
    key: AES_KEY,
    keyEncoding: 'hex',
    iv: ivHex,
    ivEncoding: 'hex',
    data: bodyHex,
    in: 'hex',
    out: 'utf8',
    padding: 'pkcs7'
  });
  return JSON.parse(text);
}

// ---------- 请求 ----------

async function callApi(path) {
  const order = [];
  if (currentHost) order.push(currentHost);
  for (const h of API_HOSTS) if (order.indexOf(h) < 0) order.push(h);

  let lastError = null;
  for (const h of order) {
    const base = 'https://' + h;
    try {
      const res = await host.http.request({
        url: base + '/api' + path,
        headers: { Accept: 'application/json', Referer: base + '/' },
        timeout: 15000
      });
      if (res.status < 200 || res.status >= 300) {
        lastError = host.error('E_HTTP', h + ' 返回 HTTP ' + res.status);
        continue;
      }
      const raw = typeof res.body === 'string' ? JSON.parse(res.body) : res.body;
      if (!raw || typeof raw !== 'object') throw host.error('E_PARSE', '接口没有返回 JSON');
      if (raw.code !== 200) throw host.error('E_HTTP', h + '：' + clean(raw.msg || ('code ' + raw.code)));

      let data = raw.data;
      const enc = pickHeader(res.headers, 'x-encrypted');
      if (enc && typeof data === 'string' && data.length > 0) data = decryptPayload(data, enc);

      if (currentHost !== h) {
        currentHost = h;
        try { host.storage.set('apiHost', h); } catch (e) { /* 无 storage 权限也无所谓 */ }
      }
      return data;
    } catch (e) {
      lastError = e;
    }
  }
  throw lastError || host.error('E_NETWORK', '所有镜像都连不上');
}

// ---------- 映射 ----------

// 封面是 "<base64 外层的 data URL>" 取前 4096 字节再与 0x12 异或后的文本。
// App 端用 transform 原生还原（PluginProviders.PluginMedia.apply case "xor"）。
const COVER_TRANSFORM = { type: 'xor', key: 18, bytes: 4096 };

function coverOf(v) {
  const u = String(v && v.vodPic ? v.vodPic : '');
  return u.indexOf('https://') === 0 ? u : '';
}

function item(v) {
  const id = String(v.vodId == null ? '' : v.vodId);
  const title = clean(v.vodName);
  if (!id || !title) return null;
  const tags = Array.isArray(v.vodClass) ? v.vodClass.map(clean).filter(Boolean) : [];
  const cover = coverOf(v);
  const out = {
    id: id,
    title: title,
    remarks: tags.slice(0, 3).join(' · '),
    tags: tags
  };
  if (cover) { out.cover = cover; out.coverTransform = COVER_TRANSFORM; }
  return out;
}

function pageOf(data, page) {
  const list = (data && Array.isArray(data.data)) ? data.data : [];
  const items = list.map(item).filter(Boolean);
  const total = Number(data && data.totalPages ? data.totalPages : 0);
  return { items: items, page: page, hasMore: total > 0 ? page < total : items.length >= PAGE_SIZE };
}

async function latest(page) {
  return pageOf(await callApi('/vod/latest?page=' + page + '&limit=' + PAGE_SIZE), page);
}

async function searchVod(keyword, page) {
  return pageOf(
    await callApi('/vod/search?keyword=' + encodeURIComponent(keyword) + '&page=' + page + '&limit=' + PAGE_SIZE),
    page
  );
}

async function configs() {
  try {
    return await callApi('/configs?platformType=h5');
  } catch (e) {
    return null;
  }
}

function streamsOf(detail) {
  const out = [];
  const seen = {};
  const groups = detail && detail.vodPlaySource ? detail.vodPlaySource : {};
  for (const key of Object.keys(groups)) {
    const list = Array.isArray(groups[key]) ? groups[key] : [];
    for (const s of list) {
      let url = String((s && (s.playUrl || s.url)) || '').split('#')[0].trim();
      if (url.indexOf('https://') !== 0 || seen[url]) continue;
      seen[url] = 1;
      const flag = clean(s.flag || '');
      out.push({
        name: clean(key) + (flag ? ' ' + flag : ''),
        url: url,
        format: url.indexOf('.m3u8') >= 0 ? 'hls' : 'mp4'
      });
    }
  }
  return out;
}

// ---------- 插件入口 ----------

export default {
  init() {
    try {
      const saved = host.storage.get('apiHost');
      if (saved && API_HOSTS.indexOf(saved) >= 0) currentHost = saved;
    } catch (e) { /* ignore */ }
  },

  async home() {
    const cfg = await configs();
    const raw = cfg && cfg.home_tags ? String(cfg.home_tags) : '';
    const tags = raw.split(',').map(function (t) { return clean(t); }).filter(Boolean).slice(0, 30);

    const categories = [{ id: 'latest', name: '最新' }];
    for (const t of tags) categories.push({ id: 'tag:' + t, name: t });

    let first = { items: [], hasMore: false };
    try { first = await latest(1); } catch (e) { /* 首页失败也别让分类空着 */ }
    return { categories: categories, items: first.items, hasMore: first.hasMore };
  },

  async category(input) {
    const id = String(input.id || '');
    const page = Number(input.page || 1);
    if (id.indexOf('tag:') === 0) return searchVod(id.slice(4), page);
    return latest(page);
  },

  async search(input) {
    const keyword = clean(input.keyword || '');
    if (!keyword) return { items: [], page: 1, hasMore: false };
    return searchVod(keyword, Number(input.page || 1));
  },

  async detail(input) {
    const id = String(input.id || '');
    if (!id) throw host.error('E_NOT_FOUND', '缺少 id');
    const d = await callApi('/vod/detail/' + encodeURIComponent(id));
    if (!d || !d.vodId) throw host.error('E_NOT_FOUND', '详情为空');
    const title = clean(d.vodName);
    const tags = Array.isArray(d.vodClass) ? d.vodClass.map(clean).filter(Boolean) : [];
    const cover = coverOf(d);
    const out = {
      title: title,
      desc: tags.length ? tags.join(' · ') : '',
      tags: tags,
      lines: [{
        name: '默认',
        episodes: [{ id: String(d.vodId), name: title }]
      }]
    };
    if (cover) { out.cover = cover; out.coverTransform = COVER_TRANSFORM; }
    return out;
  },

  async play(input) {
    const id = String(input.episodeId || input.id || '');
    if (!id) throw host.error('E_NOT_FOUND', '缺少播放 id');
    const d = await callApi('/vod/detail/' + encodeURIComponent(id));
    const streams = streamsOf(d);
    if (!streams.length) throw host.error('E_UNSUPPORTED', '这个视频没有可播放的直链');
    return { streams: streams };
  }
};
