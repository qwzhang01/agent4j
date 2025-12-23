import { TOKEN_NAME } from '@/config/global';
import axiosInstance from '@/utils/request';
import { MessagePlugin } from 'tdesign-vue';

const InitUserInfo = {
  roles: [],
};

// 定义的state初始值
const state = {
  token: localStorage.getItem(TOKEN_NAME) || '', // 默认token不走权限
  userInfo: InitUserInfo,
};

const mutations = {
  setToken(state, token) {
    state.token = token;
    localStorage.setItem(TOKEN_NAME, token);
  },
  removeToken(state) {
    localStorage.removeItem(TOKEN_NAME);
    state.token = '';
  },
  setUserInfo(state, userInfo) {
    state.userInfo = userInfo;
  },
};

const getters = {
  token: (state) => state.token,
  roles: (state) => state.userInfo?.roles,
};

const actions = {
  async login({ commit }, userInfo) {
    const { account, password } = userInfo;
    const res = await axiosInstance.post('/api/oms/account/login', {
      account,
      password,
    })
    if (res.code === 200) {
      commit('setToken', res.token);
    } else {
      throw res;
    }
  },
  async getUserInfo({ commit, state }) {
    const res = await axiosInstance.get('/api/oms/account/getInfo');
    commit('setUserInfo', res);
  },
  async logout({ commit }) {
    commit('removeToken');
    commit('setUserInfo', InitUserInfo);
  },
};

export default {
  namespaced: true,
  state,
  mutations,
  actions,
  getters,
};
