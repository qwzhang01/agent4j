import axios from 'axios';
import proxy from '../config/host';
import { MessagePlugin } from 'tdesign-vue';
import store from '@/store/index';
import { TOKEN_NAME } from '@/config/global';

const env = import.meta.env.MODE || 'development';

const API_HOST = env === 'mock' ? '/' : proxy[env].API; // 如果是mock模式 就不配置host 会走本地Mock拦截

const CODE = {
  LOGIN_TIMEOUT: 1000,
  REQUEST_SUCCESS: 200,
  REQUEST_FOBID: 1001,
};

const instance = axios.create({
  baseURL: API_HOST,
  timeout: 1000,
  withCredentials: true
});

// eslint-disable-next-line
// @ts-ignore
// axios的retry ts类型有问题
instance.interceptors.retry = 3;

instance.interceptors.request.use((config) => {
  const token = store.state.user.token
  if (token) {
    config.headers['Access-Token'] = `Bearer ${token}`;
  } else {
    const sToken = localStorage.getItem(TOKEN_NAME);
    if (sToken) {
      config.headers['Access-Token'] = `Bearer ${sToken}`;
    }
  }
  return config;
});

instance.interceptors.response.use(
  (response) => {
    if (response.status === 200) {
      const { data } = response;
      if (data.code === CODE.REQUEST_SUCCESS) {
        if (response.config.url.startsWith("/api/oms/account/login")) {
          return data;
        }

        return data.data;
      }

      MessagePlugin.warning(data.msg);
      return null;
    }
  },
  (err) => {
    const { config } = err;

    if (!config || !config.retry) return Promise.reject(err);

    config.retryCount = config.retryCount || 0;

    if (config.retryCount >= config.retry) {
      return Promise.reject(err);
    }

    config.retryCount += 1;

    const backoff = new Promise((resolve) => {
      setTimeout(() => {
        resolve({});
      }, config.retryDelay || 1);
    });

    return backoff.then(() => instance(config));
  },
);

export default instance;
