// 三环境配置：dev / test / prod
// 优先级：FORCE_ENV > storage 手动覆盖 > 小程序 envVersion 自动识别 > dev
// 说明：上线前需在「小程序后台 → 开发设置 → 服务器域名」配置 request 合法域名（https）

const ENV_STORAGE_KEY = '__zdsj_env__';
const API_TARGET_STORAGE_KEY = '__zdsj_api_target__';

// 本地联调开关：true 时强制走 dev（避免真机误连 test/prod 占位域名）
const LOCAL_DEBUG = true;

// 手动强制环境（可选）：'dev' | 'test' | 'prod'，空字符串表示不强制
const FORCE_ENV = LOCAL_DEBUG ? 'dev' : '';

// ---------- API 地址一键切换（dev 环境） ----------
// 改这一行即可默认连本地或远程：'local' | 'remote'
const DEFAULT_API_TARGET = 'local';

// 远程服务器（Nginx 80 端口，勿加 :8080）
const REMOTE_BASE_URL = 'http://62.234.81.122';

// 真机联调本地后端：填开发机局域网 IP（换 Wi-Fi 后若连不上先更新此项）
const DEV_LAN_IP = '192.168.0.107';

function getApiTarget() {
  const stored = safeGetStorageSync(API_TARGET_STORAGE_KEY);
  if (stored === 'remote' || stored === 'local') return stored;
  return DEFAULT_API_TARGET === 'remote' ? 'remote' : 'local';
}

function setApiTarget(target) {
  const normalized = target === 'remote' ? 'remote' : 'local';
  safeSetStorageSync(API_TARGET_STORAGE_KEY, normalized);
  return normalized;
}

function resolveLocalBaseUrl() {
  if (typeof wx !== 'undefined' && wx.getSystemInfoSync) {
    try {
      if (wx.getSystemInfoSync().platform === 'devtools') {
        return 'http://127.0.0.1:8080';
      }
    } catch (e) {
      // ignore
    }
  }
  return `http://${DEV_LAN_IP}:8080`;
}

function resolveDevBaseUrl() {
  if (getApiTarget() === 'remote') return REMOTE_BASE_URL;
  return resolveLocalBaseUrl();
}

const CONFIG = {
  dev: {
    get baseUrl() {
      return resolveDevBaseUrl();
    },
    subscribeTemplateId: 'dev_template_id',
  },
  test: {
    baseUrl: 'https://test-api.zhendaoshoujia.com',
    subscribeTemplateId: 'test_template_id',
  },
  prod: {
    baseUrl: 'https://api.zhendaoshoujia.com',
    subscribeTemplateId: 'prod_template_id',
  },
};

function safeGetStorageSync(key) {
  if (typeof wx === 'undefined' || !wx.getStorageSync) return '';
  try {
    return wx.getStorageSync(key);
  } catch (e) {
    return '';
  }
}

function safeSetStorageSync(key, value) {
  if (typeof wx === 'undefined' || !wx.setStorageSync) return;
  try {
    wx.setStorageSync(key, value);
  } catch (e) {
    // ignore
  }
}

function detectEnvByMiniProgram() {
  if (typeof wx === 'undefined' || !wx.getAccountInfoSync) return '';
  try {
    const info = wx.getAccountInfoSync();
    const envVersion = info && info.miniProgram && info.miniProgram.envVersion;
    if (envVersion === 'release') return 'prod';
    if (envVersion === 'trial') return 'test';
    if (envVersion === 'develop') return 'dev';
    return '';
  } catch (e) {
    return '';
  }
}

function normalizeEnv(env) {
  return Object.prototype.hasOwnProperty.call(CONFIG, env) ? env : 'dev';
}

function resolveEnv() {
  if (FORCE_ENV) return normalizeEnv(FORCE_ENV);
  const localOverride = safeGetStorageSync(ENV_STORAGE_KEY);
  if (localOverride) return normalizeEnv(localOverride);
  return normalizeEnv(detectEnvByMiniProgram() || 'dev');
}

function setEnv(env) {
  const normalized = normalizeEnv(env);
  safeSetStorageSync(ENV_STORAGE_KEY, normalized);
  return normalized;
}

function clearEnvOverride() {
  safeSetStorageSync(ENV_STORAGE_KEY, '');
}

const env = resolveEnv();
const current = CONFIG[env];

function getBaseUrl() {
  return CONFIG[resolveEnv()].baseUrl;
}

function getApiTargetLabel() {
  return getApiTarget() === 'remote' ? '远程服务器' : '本地服务器';
}

module.exports = {
  env,
  get baseUrl() {
    return getBaseUrl();
  },
  getBaseUrl,
  getApiTarget,
  setApiTarget,
  getApiTargetLabel,
  REMOTE_BASE_URL,
  subscribeTemplateId: current.subscribeTemplateId,
  setEnv,
  clearEnvOverride,
  getEnv: resolveEnv,
};
