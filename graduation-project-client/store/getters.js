const getters = {
  token: state => state.user.token,
  userId: state => state.user.userId,
  userInfo: state => state.user.info,
  platform: state => state.app.platform
}

export default getters
