<template>
  <div class="detail-base">
    <t-card v-if="baseInfoData.title" :title="baseInfoData.title" :bordered="false" class="info-block">
      <t-descriptions>
        <t-descriptions-item>
          <span>
            {{ baseInfoData.dynasty }}
          </span>
          <span>
            {{ baseInfoData.type }}
          </span>
          <span v-if="baseInfoData.auth">
            {{ baseInfoData.auth }}
          </span>
        </t-descriptions-item>
        <t-descriptions-item>
          <p v-html="baseInfoData.content" />
        </t-descriptions-item>
      </t-descriptions>
    </t-card>

    <t-card title="说明" class="container-base-margin-top" :bordered="false">
      <t-steps class="detail-base-info-steps" layout="vertical" theme="dot" :current="1">
        <t-step-item v-for="(item, index) in baseInfoData.instructions" :key="index" :title="item.title"
          :content="content">
          <template #content>
            <span v-html="item.content"></span>
          </template>
        </t-step-item>
      </t-steps>
    </t-card>
  </div>
</template>
<script lang="ts">
import { prefix } from '@/config/global';

export default {
  name: 'DetailBase',
  data() {
    return {
      prefix,
      baseInfoData: null,
    };
  },
  watch: {
    '$route': {
      immediate: true,
      handler: function (to, from) {
        this.loadDetailData()
      }
    }
  },
  mounted() {
  },
  methods: {
    loadDetailData() {
      this.$request
        .get('/api/poems/detail/' + this.$route.query.id)
        .then((res) => {
          if (res.code === 0) {
            this.baseInfoData = res.data;
          }
        })
        .catch((e: Error) => {
          console.log(e);
        })
        .finally(() => {
        });
    },
  },
};
</script>
<style lang="less" scoped>
@import './index';
</style>
