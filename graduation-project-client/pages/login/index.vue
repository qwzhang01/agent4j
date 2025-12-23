<template>
	<view class="container">
		<!-- 页面头部 -->
		<view class="header">
			<view class="title">
				<text>学号{{ register?'注册':'登录' }}</text>
			</view>
			<view class="sub-title">
				<text>{{ register?'已注册的学号请点击登录':'未注册的学号请点击注册' }}</text>
			</view>
		</view>
		<view class="login-form">
			<view class="form-item">
				<input class="form-item--input" type="number" v-model="mobile" maxlength="11" placeholder="请输入学号" />
			</view>
			<view class="form-item">
				<input class="form-item--input" type="password" v-model="smsCode" maxlength="6" placeholder="请输入密码" />
			</view>
			<view class="login-button" :class="{ disabled }" @click="handleLogin()">
				<text>{{ register?'注册':'登录' }}</text>
			</view>
			<view class="form-item--parts" @click="switchBtn">
				<text>{{ register? '去登录':'去注册' }}</text>
			</view>
		</view>

	</view>
</template>

<script>
	import store from '../../store';

	const GET_CAPTCHA = '1';
	const SUBMIT_LOGIN = '2';

	export default {
		components: {},

		props: {},

		data() {
			return {
				// 正在加载
				isLoading: false,
				// 按钮禁用
				disabled: false,
				// 手机号
				mobile: '',
				// 短信验证码
				smsCode: '',
				register: false,
			}
		},

		/**
		 * 生命周期函数--监听页面加载
		 */
		created() {},

		methods: {
			switchBtn() {
				this.isLoading =false
				this.disabled =false
				this.register = !this.register
			},
			// 表单验证
			formValidation(scene = GET_CAPTCHA) {
				const app = this
				// 验证获取短信验证码
				if (scene === GET_CAPTCHA) {
					if (!app.validteMobile(app.mobile) || !app.validteCaptchaCode(app.captchaCode)) {
						return false
					}
				}
				// 验证提交登录
				if (scene === SUBMIT_LOGIN) {
					if (!app.validteMobile(app.mobile) || !app.validteSmsCode(app.smsCode)) {
						return false
					}
				}
				return true
			},

			// 验证手机号
			validteMobile(str) {
				if (str == null || str == '') {
					this.$toast('请先输入学号')
					return false
				}
				return true
			},

			// 验证短信验证码
			validteSmsCode(str) {
				if (str == null || str == '') {
					this.$toast('请先输入密码')
					return false
				}
				return true
			},

			// 点击登录
			handleLogin() {
				const app = this
				if (!app.isLoading && !app.disabled && app.formValidation(SUBMIT_LOGIN)) {
					if (app.register) {
						app.subRegister();
					} else {
						app.submitLogin()
					}
				}
			},

			// 确认登录
			submitLogin() {
				const app = this
				app.isLoading = true
				app.disabled = true
				store.dispatch('Login', {
						smsCode: app.smsCode,
						mobile: app.mobile,
					})
					.then(result => {
						app.$toast(result.message)
						uni.$emit('syncRefresh', true)
						setTimeout(() => app.onNavigateBack(1), 2000)
					})
					.catch(err => {
						app.disabled = false
					})
					.finally(() => app.isLoading = false)
			},
			subRegister() {
				const app = this
				app.isLoading = true
				app.disabled = true
				store.dispatch('Register', {
						smsCode: app.smsCode,
						mobile: app.mobile,
					})
					.then(result => {
						app.$toast(result.message)
						uni.$emit('syncRefresh', true)
						setTimeout(() => app.onNavigateBack(1), 2000)
					})
					.catch(err => {
						app.disabled = false
					})
					.finally(() => app.isLoading = false)
			},
			/**
			 * 登录成功-跳转回原页面
			 */
			onNavigateBack(delta = 1) {
				const pages = getCurrentPages()
				if (pages.length > 1) {
					uni.navigateBack({
						delta: Number(delta || 1)
					})
				} else {
					this.$navTo('pages/index/index')
				}
			}

		}
	}
</script>

<style lang="scss" scoped>
	.container {
		padding: 100rpx 60rpx;
		min-height: 100vh;
		background-color: #fff;
	}

	// 页面头部
	.header {
		margin-bottom: 60rpx;

		.title {
			color: #191919;
			font-size: 54rpx;
		}

		.sub-title {
			margin-top: 20rpx;
			color: #b3b3b3;
			font-size: 28rpx;
		}
	}

	// 输入框元素
	.form-item {
		display: flex;
		align-items: center;
		padding: 18rpx;
		border-bottom: 1rpx solid #f3f1f2;
		margin-bottom: 30rpx;
		height: 96rpx;

		&--input {
			font-size: 28rpx;
			letter-spacing: 1rpx;
			flex: 1;
			height: 100%;
		}

		&--parts {
			min-width: 100rpx;
			letter-spacing: 1rpx;
			flex: 1;
		}

		// 图形验证码
		.captcha {
			height: 60rpx;

			.image {
				display: block;
				width: 192rpx;
				height: 100%;
			}
		}

		// 短信验证码
		.captcha-sms {
			font-size: 28rpx;
			line-height: 50rpx;
			padding-right: 20rpx;

			.activate {
				color: #fff;
			}

			.un-activate {
				color: #9e9e9e;
			}
		}
	}


	// 登录按钮
	.login-button {
		width: 100%;
		height: 86rpx;
		margin-top: 80rpx;
		background: linear-gradient(to right, #00acac, #00acac);
		color: white;
		border-radius: 80rpx;
		box-shadow: 0px 10px 20px 0px rgba(0, 0, 0, 0.1);
		letter-spacing: 5rpx;
		display: flex;
		justify-content: center;
		align-items: center;

		// 禁用按钮
		&.disabled {
			opacity: 0.6;
		}
	}
</style>