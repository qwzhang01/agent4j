<template>
	<view class="container">
		<!-- 页面头部 -->
		<view class="header">
			<view class="title">
				<text>信息修改</text>
			</view>
			<view class="sub-title">
				<text>修改个人信息</text>
			</view>
		</view>
		<view class="login-form">
			<view class="form-item">
				<input class="form-item--input" type="text" v-model="name" maxlength="11" placeholder="请输入姓名" />
			</view>
			<view class="form-item">
				<input class="form-item--input" type="text" v-model="email" maxlength="6" placeholder="请输入邮箱" />
			</view>
            <view class="form-item">
				<input class="form-item--input" type="text" v-model="phone" maxlength="6" placeholder="请输入手机号码" />
			</view>
			<view class="login-button" :class="{ disabled }" @click="handleLogin()">
				<text>提交</text>
			</view>
		</view>

	</view>
</template>
<script>
import { userEdit as userEditApi } from '@/service/index'
import store from "../../store";

export default {
	components: {},

	props: {},

	data() {
		return {
			// 正在加载
			isLoading: false,
			// 按钮禁用
			disabled: false,
			name: '',
			email: '',
			phone: ''
		}
	},

	/**
	 * 生命周期函数--监听页面加载
	 */
	created() {
        const user = store.getters.userInfo
        this.name = user.name;
        this.email = user.email;
        this.phone = user.phone;
    },

	methods: {
		// 表单验证
		formValidation() {
			const app = this
			// 验证获取短信验证码
				if (!app.validteName(app.name) || !app.validtePhone(app.phone)|| !app.validteEmail(app.email)) {
					return false
				}
			return true
		},

		// 验证手机号
		validteName(str) {
			if (str == null || str == '') {
				this.$toast('请先输入姓名')
				return false
			}
			return true
		},
        validtePhone(str) {
			if (str == null || str == '') {
				this.$toast('请先输入手机号码')
				return false
			}
			return true
		},
        validteEmail(str) {
			if (str == null || str == '') {
				this.$toast('请先输入邮箱地址')
				return false
			}
			return true
		},

		// 点击登录
		handleLogin() {
			const app = this
			if (!app.isLoading && !app.disabled && app.formValidation()) {
				app.submitLogin()
			}
		},

		// 确认登录
		submitLogin() {
			const app = this
			app.isLoading = true
			app.disabled = true
            userEditApi({
name:                app.name,
email:                app.email,
phone:                app.phone,
            }).then(res=>{
                    if(res.code == 200){
                        this.onNavigateBack()
                    }
            })
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
				this.$navTo('pages/user/index')
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