<template>
  <view class="container">
    <block v-if="storeInfo">
      <Location :storeInfo="storeInfo" :bgColor="bgColor" />
    </block>
    <block>
      <Search tips="请输入搜索关键字" @event="$navTo('pages/search/index')" />
    </block>
    <block>
      <Banner :itemStyle="options.bannerStyle" :params="options.bannerParam" :autoplay="autoplay" :dataList="banner"
        @change="bannerSwiper" />
    </block>
    <block>
      <Blank :itemStyle="options.blankStyle" />
    </block>
    <block>
      <NavBar :itemStyle="options.navStyle" :params="{}" :dataList="options.navBar" />
    </block>
    <block>
      <Blank :itemStyle="options.blankStyle" />
    </block>
    <block>
      <Goods :itemStyle="options.goodsStyle" :isReflash="isReflash" ref="mescrollItem" :params="options.goodsParams" />
    </block>
  </view>
</template>

<script>
import { setCartTabBadge, showMessage } from '@/utils/app'
import Location from '@/components/page/location'
import Search from '@/components/search'
import Banner from '@/components/page/banner'
import NavBar from '@/components/page/navBar'
import Blank from '@/components/page/blank'
import Goods from '@/components/page/goods'
import { store, banner } from '@/service/index'
import MescrollCompMixin from "@/components/mescroll-uni/mixins/mescroll-comp.js";
import config from '@/config'

const App = getApp()

export default {
  mixins: [MescrollCompMixin],
  components: {
    Location,
    Search,
    Banner,
    NavBar,
    Blank,
    Goods
  },
  data() {
    return {
      options: {
        "blankStyle": {
          "height": "5",
          "background": "#ffffff",
        },
        "navBar": [{
          "imgUrl": config.apiUrl + "icon/pay.png",
          "imgName": "icon-1.png",
          "linkUrl": "pages\/pay\/index",
          "text": "买单支付",
          "tip": "支付攒积分",
          "color": "#666666"
        }, {
          "imgUrl": config.apiUrl + "icon/coupon.png",
          "imgName": "icon-1.png",
          "linkUrl": "pages\/coupon\/list?type=C,D",
          "text": "领券中心",
          "tip": "积分换好礼",
          "color": "#666666"
        }, {
          "imgUrl": config.apiUrl + "icon/charge.png",
          "imgName": "icon-1.png",
          "linkUrl": "pages\/coupon\/list?type=P",
          "text": "预存充值",
          "tip": "充值有优惠",
          "color": "#666666",
        }, {
          "imgUrl": config.apiUrl + "icon/activity.png",
          "imgName": "icon-1.png",
          "linkUrl": "pages\/coupon\/list?type=T",
          "text": "热门活动",
          "tip": "抽奖、次卡等",
          "color": "#666666"
        }],
        "goodsStyle": {
          "background": "#F6F6F6",
          "display": "list",
          "column": 1,
          "show": ["goodsName", "goodsPrice", "linePrice", "sellingPoint", "goodsSales"]
        },
        "goodsParams": {
          "source": "auto",
          "auto": {
            "category": 0,
            "goodsSort": "all",
            "showNum": 40
          }
        },
        "bannerStyle": {
          "btnColor": "#ffffff",
          "btnShape": "round",
          "interval": 2.5,

        },
        "bannerParam": {
          "interval": 2000
        },
        "navStyle": {
          "background": "#ffffff",
          "rowsNum": "2",
        }
      },
      banner: [],
      autoplay: true,
      goods: [],
      storeInfo: null,
      isReflash: false,
      bgColor: ''
    }
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad({ storeId }) {
    this.autoplay = true
    storeId = storeId ? parseInt(storeId) : 0;
    if (storeId > 0) {
      uni.setStorageSync('storeId', storeId);
      uni.setStorageSync("reflashHomeData", true);
    } else {
      this.getPageData();
    }

    uni.getLocation({
      type: 'wgs84',
      //设置该参数为true可直接获取经纬度及城市信息
      geocode: true,
      success: function (res) {
        that.addrDel = res;
      },
      fail: function (e) {
        uni.showToast({
          title: '获取地址失败，将导致部分功能不可用',
          icon: 'none'
        });
      }
    });
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    this.autoplay = true
    const app = this;
    showMessage();
    setCartTabBadge();
    // 获取店铺信息
    uni.getLocation({
      type: 'gcj02',
      success(res) {
        uni.setStorageSync('latitude', res.latitude);
        uni.setStorageSync('longitude', res.longitude);
        app.getStore();
      },
      fail(e) {
        app.getStore();
      }
    })
  },
  onHide() {
    this.autoplay = false
  },
  methods: {
    bannerSwiper(item) {
      this.bgColor = item.colorHex
    },
    /**
     * 加载页面数据
     * @param {Object} callback
     */
    getPageData(callback) {
      banner(5).then(res => {
        this.banner = res;
        uni.removeStorageSync("reflashHomeData");
        this.isReflash = false;
      })
    },

    /**
     * 下拉刷新
     */
    onPullDownRefresh() {
      // 获取数据
      this.getPageData(() => {
        uni.stopPullDownRefresh()
      })
    },

    /**
     * 获取默认店铺
     * */
    getStore() {
      const latitude = uni.getStorageSync('latitude');
      const longitude = uni.getStorageSync('longitude');
      store({ latitude, longitude }).then(res => {
        if (res && res.length > 0) {
          let store = res[0];
          this.storeInfo = store;
          if (this.storeInfo) {
            uni.setStorageSync("storeId", store.id);
            uni.setStorageSync("merchantNo", store.id);

            this.isReflash = uni.getStorageSync("reflashHomeData");
            if (this.isReflash === true) {
              this.getPageData();
            }
          }
        }
      })
    }
  },

  /**
   * 分享当前页面
   */
  onShareAppMessage() {
    const app = this
    return {
      title: "fuint会员系统",
      path: "/pages/index/index?" + app.$getShareUrlParams()
    }
  },

  /**
   * 分享到朋友圈
   * 本接口为 Beta 版本，暂只在 Android 平台支持，详见分享到朋友圈 (Beta)
   * https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/share-timeline.html
   */
  onShareTimeline() {
    const app = this
    const { page } = app
    return {
      title: page.params.share_title,
      path: "/pages/index/index?" + app.$getShareUrlParams()
    }
  }

}
</script>

<style lang="scss" scoped>
.container {
  background: #fff;
}
</style>
