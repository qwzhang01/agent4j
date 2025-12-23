<template>
  <t-row :gutter="[16, 16]">
    <t-col :flex="3">
      <div class="user-left-greeting">
        <div>
          Hi，Image
          <span class="regular"> 下午好，今天是你加入鹅厂的第 100 天～</span>
        </div>
        <img src="@/assets/assets-tencent-logo.png" class="logo" />
      </div>

      <t-card class="user-info-list" title="个人信息" :bordered="false">
        <template #option>
          <t-button theme="default" shape="square" variant="text">
            <edit-icon size="18" />
          </t-button>
        </template>
        <t-row class="content" justify="space-between">
          <t-col class="contract" :span="4">
            <div class="contract-title">ID</div>
            <div class="contract-detail">
              {{ data.id }}
            </div>
          </t-col>
          <t-col class="contract" :span="4">
            <div class="contract-title">用户名</div>
            <div class="contract-detail">
              {{ data.username }}
            </div>
          </t-col>
          <t-col class="contract" :span="4">
            <div class="contract-title">开始时间</div>
            <div class="contract-detail">
              {{ data.startTime }}
            </div>
          </t-col>
          <t-col class="contract" :span="4">
            <div class="contract-title">到期时间</div>
            <div class="contract-detail">
              {{ data.endTime }}
            </div>
          </t-col>
          <t-col class="contract" :span="4">
            <div class="contract-title">邮箱</div>
            <div class="contract-detail">
              {{ data.email }}
            </div> </t-col
          ><t-col class="contract" :span="4">
            <div class="contract-title">手机</div>
            <div class="contract-detail">
              {{ data.phone }}
            </div>
          </t-col>
        </t-row>
      </t-card>
    </t-col>
    <t-col :flex="1">
      <t-card class="user-intro" :bordered="false">
        <t-avatar size="90px">T</t-avatar>
        <div class="name">My Account</div>
        <div class="position">XXG 港澳业务拓展组员工 直客销售</div>
      </t-card>
    </t-col>
  </t-row>
</template>
<script lang="ts">
import { mapState } from 'vuex';

export default {
  name: 'UserIndex',
  components: {},
  data() {
    return {
      data: {},
    };
  },
  computed: {
    ...mapState('setting', ['brandTheme', 'mode']),
  },
  watch: {},
  mounted() {
    this.loadData();
    this.ossSign();
  },
  methods: {
    ossSign() {
      this.$request.get("/api/common/upload/sign").then((res) => {
        
      });
    },
    loadData() {
      this.$request
        .get('/api/tenant/user')
        .then((res) => {
          if (res.code === 0) {
            this.data = res.data;
          }
        })
        .catch((e: Error) => {
          console.log(e);
        })
        .finally(() => {
          this.dataLoading = false;
        });
    },
  },
};
</script>
<style lang="less" scoped>
@import url('./index.less');
</style>
