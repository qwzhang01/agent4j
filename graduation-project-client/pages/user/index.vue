<template>
  <view v-if="!isLoading" class="container">
    <!-- 页面头部 -->
    <view class="main-header">
      <!-- 用户信息 -->
      <view class="user-info">
        <view class="user-avatar" @click="onUserInfo">
          <image
            class="image" src="@/static/tabbar/user-active.png"
          ></image>
        </view>

        <view class="user-content" @click="onUserInfo">
          <view class="nick-name">{{
            userInfo.name ? userInfo.name : "未登录"
          }}</view>
          <view class="login-tips" v-if="!isLogin">(点击头像登录)</view>
          <!-- 会员等级 -->
          <view v-if="userInfo.account > 0" class="user-grade">
            <view class="user-grade_icon">
              <image class="image" :src="gradeSrc" />
            </view>
            <view class="user-grade_name">
              <text>{{ userInfo.account }}</text>
            </view>
          </view>
          <!-- 会员无等级时显示手机号 -->
          <view  class="mobile">{{ userInfo.phone }}</view>
          <view class="active-time" v-if="userInfo.email">{{
            userInfo.email
          }}</view>
        </view>
       
      </view>
      <view class="user-no">
        <view class="no" v-if="userInfo.id"
          >会员号：{{ userInfo.id ? userInfo.id : "-" }}</view
        >
      </view>
    </view>

    <!-- 我的服务 -->
    <view class="my-service">
      <view class="service-title">我的服务</view>
      <view class="service-content clearfix">
        <block v-for="(item, index) in service" :key="index">
          <view
            v-if="item.type == 'link'"
            class="service-item"
            @click="handleService(item)"
          >
            <view class="item-icon">
              <text class="iconfont" :class="[`icon-${item.icon}`]"></text>
            </view>
            <view class="item-name">{{ item.name }}</view>
          </view>
          <view
            v-if="item.type == 'button' && $platform == 'MP-WEIXIN'"
            class="service-item"
          >
            <button class="btn-normal" :open-type="item.openType">
              <view class="item-icon">
                <text class="iconfont" :class="[`icon-${item.icon}`]"></text>
              </view>
              <view class="item-name">{{ item.name }}</view>
            </button>
          </view>
        </block>
        <block>
          <view
            v-if="isMerchant == true"
            class="service-item"
            @click="handleService({ url: 'pages/merchant/index' })"
          >
            <view class="item-icon">
              <text class="iconfont icon-dianpu"></text>
            </view>
            <view class="item-name">商户管理</view>
          </view>
        </block>
      </view>
    </view>

    <view class="my-recommend"></view>
  </view>
</template>

<script>
import SettingKeyEnum from "@/common/enum/setting/Key";
import { checkLogin, showMessage } from "@/utils/app";
import { userInfo } from "@/service/index";
import config from "@/config";
import store from "../../store";

// 订单操作
const orderNavbar = [
  {
    id: "all",
    name: "全部订单",
    icon: "qpdingdan",
  },
  {
    id: "toPay",
    name: "待支付",
    icon: "daifukuan",
    count: 0,
  },
  {
    id: "paid",
    name: "已支付",
    icon: "daishouhuo",
  },
];

/**
 * 我的服务
 * id: 标识; name: 标题名称; icon: 图标; type 类型(link和button); url: 跳转的链接
 */
const service = [
  // {
  //   id: "myCoupon",
  //   name: "卡券核销",
  //   icon: "youhuiquan",
  //   type: "link",
  //   url: "pages/coupon/receive",
  // },
  // {
  //   id: "coupon",
  //   name: "转赠记录",
  //   icon: "lingquan",
  //   type: "link",
  //   url: "pages/give/index",
  // },
  // {
  //   id: "points",
  //   name: "我的积分",
  //   icon: "jifen",
  //   type: "link",
  //   url: "pages/points/detail",
  // },
  {
    id: "help",
    name: "我的信息",
    icon: "bangzhu",
    type: "link",
    url: "pages/user/form",
  },
  {
    id: "contact",
    name: "消息中心",
    icon: "kefu",
    type: "link",
    url: "pages/notice/index"
    // type: "button",
    // openType: "contact",
  },
  // {
  //   id: "address",
  //   name: "收货地址",
  //   icon: "shouhuodizhi",
  //   type: "link",
  //   url: "pages/address/index",
  // },
  // {
  //   id: "refund",
  //   name: "售后服务",
  //   icon: "shouhou",
  //   type: "link",
  //   url: "pages/refund/index",
  // },
  // {
  //   id: "setting",
  //   name: "个人信息",
  //   icon: "shezhi1",
  //   type: "link",
  //   url: "pages/user/setting",
  // },
];

export default {
  components: {
  },
  data() {
    return {
      defaultAvator: config.apiUrl + "icon/avatar.png",
      gradeSrc:
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAMAAABEpIrGAAAA0lBMVEUAAAD/tjL/tzH/uDP/uC7/tjH/tzH/tzL/tTH+tTL+tjP/tDD/tTD+tzD/tjL/szD/uDH/tjL/tjL+tjD/tjT/szb/tzL/tTL+uTH+tjL/tjL/tjL/tTT/tjL/tjL+tjH/uTL/vDD/tjL/tjH/tzL9uS//tTL/nBr/sS7/tjH/ujL/szD/uTv+rzf/tzL+tzH+vDP+uzL+tjP+ry7+tDL9ki/7szf/sEX/tTL/tjL+tjL/tTH/tTT/tzH/tzL/tjP/sTX/uTP/wzX+rTn/vDX9vC8m8ckhAAAAOXRSTlMAlnAMB/vjxKWGMh0S6drMiVxPRkEY9PLy0ru0sKagmo5+dGtgVCMgBP716eXWyMGxqJGRe2o5KSmFNjaYAAABP0lEQVQ4y8XS13KDMBAF0AWDDe4t7r3ETu9lVxJgJ/n/X8rKAzHG5TE+Twz3zki7I/g/KXdghIbGJewrU4yzn08Ebgl6TuZzzuOC6W5es3HX6qsSz3NFShRU0MpucytDmOSpu3yULx3CA9RD1HjVedc0jSjqm6ZzhUjDsFDQhSp/OKj5GQvg0+ZCOixsbtDLAeTTOm/yGi8GyIphIVsgH737FEDV44LJa88IRKK/SetrwT9G/GUIr6vXjoy4GXn7+RboVXnghuSjaoGecwQxL2su3CwAKlO+QFoqxI4FMctHQhQd2OhxTu184jWUlI+rMTBTn1/IQcJHQ6GQdZ7pWiDaNdhTt330efISeiqYwQEzQpTlsURJLhzkEmpCPsERfeIUVyXr6MNuIyp5uziW6xURtt7hhGwzmMNJExfO4Bd9X0ZPqAxdNwAAAABJRU5ErkJggg==",
      // 枚举类
      SettingKeyEnum,
      // 当前运行的终端 (此处并不冗余,因为微信小程序端view层无法直接读取$platform)
      $platform: this.$platform,
      // 正在加载
      isLoading: true,
      // 是否已登录
      isLogin: false,
      // 系统设置
      setting: {},
      // 当前用户信息
      // 当前用户待处理的订单数量
      userInfo: {},
      isMerchant: false,
      // 我的服务
      service,
      // 订单操作
      orderNavbar,
      current: 0,
      // 显示、隐藏弹窗
      showPopup: false,
      memberGrade: [],
      curGrade: {},
    };
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow(options) {
    // 获取页面数据
    this.getPageData();
    // 判断是否已登录
    this.isLogin = checkLogin();
    // 消息显示
    showMessage();
  },

  methods: {
    // 获取页面数据
    getPageData(callback) {
      const app = this;
      app.isLoading = true;
      Promise.all([app.getSetting(), app.getUserInfo()])
        .then((result) => {
          // 初始化我的服务数据
          //app.initService();
          // 初始化订单操作数据
          // app.initOrderTabbar();
          // 执行回调函数
          callback && callback();
        })
        .catch((err) => {
          console.log("catch", err);
        })
        .finally(() => {
          app.isLoading = false;
        });
    },

    // 初始化我的服务数据
    initService() {
      const app = this;
      const newService = [];
      service.forEach((item) => {
        if (item.id === "points") {
          item.name = "我的积分";
        }
        newService.push(item);
      });
      app.service = newService;
    },

    // 初始化订单操作数据
    initOrderTabbar() {
      const app = this;
      const newOrderNavbar = [];
      orderNavbar.forEach((item) => {
        if (item.hasOwnProperty("count")) {
          item.count = app.isLogin ? app.userInfo.unPayCount : 0;
        }
        newOrderNavbar.push(item);
      });
      app.orderNavbar = newOrderNavbar;
    },

    // 获取设置
    getSetting() {
      const app = this;
      app.setting = {};
    },

    // 获取当前用户信息
    getUserInfo() {
      const app = this;
      app.showPopup = false;
      return new Promise((resolve, reject) => {
        userInfo()
          .then((result) => {
            if (result) {
              app.userInfo = result.data;
              store.commit("setInfo", app.userInfo);
              app.isLogin = true;
            }
            resolve(app.userInfo);
          })
          .catch((err) => {
            if (err.msg) {
              app.isLogin = false;
              resolve(err.msg);
            } else {
              reject(err);
            }
          });
      });
    },
   

    // 跳转会员设置页面
    onUserInfo() {
      // empty
    },

    // 跳转到服务页面
    handleService({ url }) {
      this.$navTo(url);
    },
  },

  /**
   * 下拉刷新
   */
  onPullDownRefresh() {
    // 获取首页数据
    this.getPageData(() => {
      uni.stopPullDownRefresh();
    });
  },
};
</script>

<style lang="scss" scoped>
// 页面头部
.main-header {
  background: url("~@/static/background/user-header.png") no-repeat;
  height: 380rpx;
  background-size: cover;
  overflow: hidden;
  display: block;
  align-items: center;
  margin: 10rpx 25rpx 10rpx 25rpx;
  border-radius: 10rpx;

  .user-info {
    display: block;
    height: 200rpx;
    margin: 20rpx;
    margin-left: 20rpx;

    .user-avatar {
      padding-top: 10rpx;
      width: 50rpx;
      margin-top: 70rpx;
      float: left;

      .image {
        display: block;
        width: 100rpx;
        height: 100rpx;
        border-radius: 999rpx;
      }
    }

    .user-content {
      display: block;
      justify-content: center;
      margin-top: 80rpx;
      margin-left: 60rpx;
      float: left;
      color: #ffffff;
      max-width: 300rpx;

      .nick-name {
        font-size: 32rpx;
        font-weight: bold;
        max-width: 270rpx;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .mobile {
        margin-top: 15rpx;
        font-size: 26rpx;
      }

      .user-grade {
        display: block;
        align-items: center;
        background: #3c3c3c;
        margin-top: 8rpx;
        border-radius: 10rpx;
        padding: 5rpx 12rpx;
        width: 80%;
        min-width: 160rpx;
        height: 40rpx;

        .user-grade_icon .image {
          display: block;
          width: 32rpx;
          height: 32rpx;
          float: left;
        }

        .user-grade_name {
          margin-left: 5rpx;
          font-size: 24rpx;
          color: #eee0c3;
          float: left;
        }
      }

      .active-time {
        margin-top: 3rpx;
      }

      .login-tips {
        margin-top: 9rpx;
        font-size: 25rpx;
      }
    }

    .amount-info {
      margin-top: 80rpx;
      margin-left: 70rpx;
      color: #fff;
      display: block;
      float: left;
      max-width: 120rpx;

      .amount-tip {
        font-size: 24rpx;
      }

      .amount-num {
        margin-top: 10rpx;
        font-weight: bold;
        font-size: 48rpx;
      }

      .point-amount {
        display: block;
        margin-top: 2px;
        width: 100px;
      }
    }

    .pay-qr {
      color: #ffffff;
      margin-top: 10rpx;
      margin-left: 50rpx;
      text-align: center;
      width: 50rpx;
      float: right;

      .qrcode {
        display: block;
        font-size: 40rpx;
      }
    }
  }

  .user-no {
    display: block;
    font-size: 25rpx;
    margin: 110rpx 0rpx 0rpx 20rpx;
    color: #ffffff;

    .no {
      float: left;
    }

    .recharge {
      float: right;
      margin-right: 20rpx;
    }
  }
}

// 我的资产
.my-asset {
  display: flex;
  background: #fff;
  margin: 10rpx 20rpx 10rpx 20rpx;
  padding: 40rpx 0;
  border: 2rpx #f5f5f5 solid;

  .asset-right {
    width: 200rpx;
    border-left: 1rpx solid #eee;
  }

  .asset-left-item {
    text-align: center;
    color: #666;
    padding: 0 22rpx;
    width: 33%;

    .item-value {
      font-size: 35rpx;
      color: #f03c3c;
      font-weight: bold;
    }

    .item-name {
      font-size: 25rpx;
      margin-top: 6rpx;
    }
  }
}

// 订单操作
.order-navbar {
  display: flex;
  margin: 12rpx auto 10rpx auto;
  padding: 20rpx 0;
  width: 94%;
  box-shadow: 0 1rpx 5rpx 0px rgba(0, 0, 0, 0.05);
  font-size: 30rpx;
  border-radius: 5rpx;
  background: #fff;
  border: 2rpx #f5f5f5 solid;

  &-item {
    position: relative;
    width: 33%;

    .item-icon {
      text-align: center;
      margin: 0 auto;
      padding: 10rpx 0;
      color: #545454;
      font-size: 48rpx;
      font-weight: bold;
    }

    .item-name {
      font-size: 24rpx;
      color: #545454;
      text-align: center;
      margin-right: 10rpx;
    }

    .order-badge {
      position: absolute;
      top: 0;
      right: 58rpx;
      font-size: 20rpx;
      background: #fa5151;
      text-align: center;
      line-height: 30rpx;
      color: #fff;
      border-radius: 50%;
      min-width: 36rpx;
      padding: 6rpx 13rpx 6rpx 13rpx;
    }
  }
}

// 我的服务
.my-service {
  margin: 0rpx auto 20rpx auto;
  border: 2rpx #f5f5f5 solid;
  background: #fff;
  padding: 10rpx 0rpx;
  width: 94%;
  box-shadow: 0 1rpx 5rpx 0px rgba(0, 0, 0, 0.05);
  border-radius: 5rpx;
  display: block;

  .service-title {
    padding-left: 20rpx;
    margin-bottom: 30rpx;
    font-size: 28rpx;
  }

  .service-content {
    .service-item {
      width: 25%;
      float: left;
      margin-bottom: 25rpx;

      .item-icon {
        text-align: center;
        margin: 0 auto;
        padding: 10rpx 0;
        color: #ff3800;
        font-size: 40rpx;
      }

      .item-name {
        font-size: 24rpx;
        color: #545454;
        text-align: center;
        margin-right: 10rpx;
      }
    }
  }
}

// 推荐信息
.my-recommend {
  height: 20rpx;
}

// 会员升级
.member-update {
  margin: 22rpx auto 0rpx auto;
  padding: 20rpx 0;
  border-radius: 5rpx;
  box-shadow: 0 1rpx 5rpx 0px rgba(0, 0, 0, 0.05);
  background: #fff;
  width: 94%;
  text-align: center;

  .update-title {
    padding-left: 20rpx;
    margin-bottom: 30rpx;
    font-size: 28rpx;
    text-align: left;
  }

  .recharge {
    position: relative;
    margin-bottom: 35rpx;
    display: flex;
    flex-direction: row;
    align-items: center;

    &-tag {
      position: absolute;
      top: -2rpx;
      left: -2rpx;
      width: 170rpx;
      height: 36rpx;
      display: flex;
      flex-direction: row;
      align-items: center;
      justify-content: center;
      background-image: url("~@/static/user/tag.png");
      background-size: 100%;

      &-text {
        font-size: 20rpx;
        color: #ffffff;
        text-align: center;
      }
    }

    &-item {
      position: relative;
      padding: 40rpx 0;
      margin-left: 15rpx;
      width: 29.33%;
      height: 270rpx;
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      border: solid 1rpx #cbccce;
      border-radius: 12rpx;

      &-active {
        border: solid 2rpx #edd2a9;
        background-color: #fbf1e5;
      }

      &-duration {
        margin-bottom: 30rpx;
        font-size: 26rpx;
        color: #1c1c1c;
      }

      &-price {
        margin-bottom: 20rpx;
        display: flex;
        flex-direction: row;
        align-items: baseline;

        &-text {
          font-size: 48rpx;
          color: #e3be83;
        }
      }

      &-des {
        font-size: 22rpx;
        color: #a5a3a2;
      }
    }
  }
}
</style>