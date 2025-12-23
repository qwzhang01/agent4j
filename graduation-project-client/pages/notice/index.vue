<template>
  <mescroll-body ref="mescrollRef" :sticky="true" @init="mescrollInit" :down="{ use: false }" :up="upOption"
    @up="upCallback">

    <view class="tabs-wrapper">
      <scroll-view class="scroll-view" scroll-x>
        <view class="tab-item" :class="{ active: curId == item.key }" @click="onSwitchTab(item.key)"
          v-for="(item, index) in categoryList" :key="index">
          <view class="value"><text>{{ item.value }}</text></view>
        </view>
      </scroll-view>
    </view>

    <!-- 文章列表 -->
    <view class="article-list">
      <view class="article-item show-type" v-for="(item, index) in list.records" :key="index"
        @click="onTargetDetail(item.id)">
        <block>
          <view class="article-item-left flex-box" @click="read(item.id, item.readFlag)">
            <view class="article-item-title twolist-hidden">
              <text :style="'color:' + (item.readFlag ? 'green' : 'red')">【{{ item.readFlag ? '已读' : '未读' }}】</text>
              <text>{{ item.title }}</text>
            </view>
            <view class="article-item-footer m-top10">
              <text class="article-views f-24 col-8">{{ item.profile }}</text>
            </view>
          </view>
          <view class="article-item-image">
            <!-- <image class="image" :src="item.image"></image> -->
          </view>
        </block>
      </view>
    </view>
  </mescroll-body>
</template>

<script>
import MescrollMixin from '@/components/mescroll-uni/mescroll-mixins'
import { getEmptyPaginateObj, getMoreListData } from '@/utils/app'
import { noticeList as noticeListApi, readNotice as readApi } from '@/service/index.js';
const pageSize = 15

export default {
  components: {
  },
  mixins: [MescrollMixin],
  data() {
    return {
      // 分类列表
      categoryList: [{
        key: 'all',
        value: '全部',
      }, {
        key: 'unRead',
        value: '未读',
      }, {
        key: 'read',
        value: '已读',
      }],
      // 文章列表
      list: getEmptyPaginateObj(),
      // 当前选中的分类id (0则代表首页)
      curId: 'all',
      // 上拉加载配置
      upOption: {
        // 首次自动执行
        auto: true,
        // 每页数据的数量; 默认10
        page: { size: pageSize },
        // 数量要大于3条才显示无更多数据
        noMoreSize: 12,
      }
    }
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    const app = this;
    if (options.categoryId) {
      app.curId = options.categoryId;
    }
  },
  onShow() {
    this.upCallback(1)
  },
  methods: {
    read(id, readFlag) {
      if (readFlag) {
        return;
      }
      readApi(id).then(res => {
        if (res.code == 200) {
          this.$toast('已阅读')
        }
        this.getArticleList(1);
      })
    },
    /**
     * 上拉加载的回调 (页面初始化时也会执行一次)
     * 其中page.num:当前页 从1开始, page.size:每页数据条数,默认10
     * @param {Object} page
     */
    upCallback(page) {
      this.getArticleList(page)
    },

    /**
     * 获取文章列表
     * @param {Number} pageNo 页码
     */
    getArticleList(pageNo = 1) {
      const app = this
      noticeListApi(app.curId, { size: 15, current: pageNo })
        .then(result => {
          // 合并新数据
          app.list = getMoreListData(result.data, app.list, pageNo);
        })
    },

    // 切换选择的分类
    onSwitchTab(categoryId = 'all') {
      const app = this;
      // 切换当前的分类ID
      app.curId = categoryId;
      // 刷新列表数据
      app.upCallback(1)
    },

    // 跳转文章详情页
    onTargetDetail(articleId) {
      this.$navTo('pages/article/detail', { articleId });
    }
  },

  /**
   * 分享当前页面
   */
  onShareAppMessage() {
    return {
      title: '文章首页',
      path: "/pages/article/index?" + this.$getShareUrlParams()
    }
  },

  /**
   * 分享到朋友圈
   * 本接口为 Beta 版本，暂只在 Android 平台支持，详见分享到朋友圈 (Beta)
   * https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/share-timeline.html
   */
  onShareTimeline() {
    return {
      title: '文章首页',
      path: "/pages/article/index?" + this.$getShareUrlParams()
    }
  }

}
</script>

<style lang="scss" scoped>
/* 顶部选项卡 */

.container {
  min-height: 100vh;
}

.tabs-wrapper {
  position: sticky;
  top: var(--window-top);
  display: flex;
  width: 100%;
  height: 88rpx;
  color: #333;
  font-size: 28rpx;
  background: #fff;
  border-bottom: 1rpx solid #e4e4e4;
  z-index: 100;
  overflow: hidden;
  white-space: nowrap;
}

.tab-item {
  display: inline-block;
  padding: 0 15rpx;
  text-align: center;
  min-width: 30%;
  height: 87rpx;
  line-height: 88rpx;
  box-sizing: border-box;

  .value {
    height: 100%;
  }

  &.active .value {
    color: #fd4a5f;
    border-bottom: 4rpx solid #fd4a5f;
  }
}

/* 文章列表 */
.article-list {
  padding-top: 20rpx;
  line-height: 1;
  background: #f7f7f7;
}

.article-item {
  margin-bottom: 20rpx;
  padding: 30rpx;
  background: #fff;

  &:last-child {
    margin-bottom: 0;
  }

  .article-item-title {
    max-height: 80rpx;
    font-size: 32rpx;
    color: #333;
  }

  .article-item-image .image {
    display: block;
    border-radius: 8rpx;
    height: 140rpx;
    width: 180rpx;
    border: 2rpx solid #cccccc;
  }
}

.show-type {
  display: flex;

  .article-item-left {
    padding-right: 20rpx;
  }

  .article-item-title {
    min-height: 72rpx;
  }
}
</style>
