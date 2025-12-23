<template>
  <div class="list-common-table">
    <t-form ref="form" :data="formData" :label-width="80" colon @reset="onReset" @submit="onSubmit"
      :style="{ marginBottom: '8px' }">
      <t-row>
        <t-col :span="10">
          <t-row :gutter="[16, 24]">
            <t-col :flex="1" :span="4">
              <t-form-item label="用户" name="name">
                <t-input v-model="formData.name" class="form-item-content" type="search" placeholder="请输入用户账号"
                  :style="{ minWidth: '134px' }" />
              </t-form-item>
            </t-col>
          </t-row>
        </t-col>
        <t-col :span="2" class="operation-container">
          <t-button theme="primary" type="submit" :style="{ marginLeft: '8px' }"> 查询 </t-button>
          <t-button type="reset" variant="base" theme="default"> 重置 </t-button>
        </t-col>
      </t-row>
    </t-form>
    <div class="table-container">
      <t-table :data="data" :columns="columns" :rowKey="rowKey" :verticalAlign="verticalAlign" :hover="hover"
        :pagination="pagination" @page-change="rehandlePageChange" @change="rehandleChange" :loading="dataLoading"
        :headerAffixedTop="true" :headerAffixProps="{ offsetTop, container: getContainer }">
      </t-table>
    </div>
  </div>
</template>
<script lang="ts">
import { prefix } from '@/config/global';
import Trend from '@/components/trend/index.vue';

export default {
  name: 'list-table',
  components: {
    Trend,
  },
  data() {
    return {
      prefix,
      formData: {
        name: '',
      },
      data: [],
      dataLoading: false,
      value: 'first',
      columns: [
        {
          title: '账号',
          colKey: 'account',
        },
        {
          title: '姓名',
          colKey: 'name',
        },
      ],
      rowKey: 'index',
      verticalAlign: 'top',
      hover: true,
      pagination: { current: 1, pageSize: 10, total: 0 },
    };
  },
  computed: {
    offsetTop() {
      return this.$store.state.setting.isUseTabsRouter ? 48 : 0;
    },
  },
  mounted() {
    this.loadData()
  },
  methods: {
    loadData() {
      this.dataLoading = true;
      this.$request
        .post('/api/oms/account/list', {
          ...this.formData,
          size: this.pagination.pageSize,
          current: this.pagination.current
        })
        .then((res) => {
          if (res) {
            this.data = res.records;
            this.data = res;
            this.pagination = {
              ...this.pagination,
              total: parseInt(res.total),
            };
          }
        })
        .catch((e: Error) => {
          console.log(e);
        })
        .finally(() => {
          this.dataLoading = false;
        });
    },
    getContainer() {
      return document.querySelector('.tdesign-starter-layout');
    },
    onReset(data) {
      this.loadData();
    },
    onSubmit(data) {
      this.loadData();
    },
    rehandlePageChange(curr, pageInfo) {
      console.log('分页变化', curr, pageInfo);
    },
    rehandleChange(changeParams, triggerAndData) {
      const { pagination } = changeParams;
      const { current, pageSize } = pagination;
      this.pagination = {
        ...this.pagination,
        current,
        pageSize,
      };
      this.loadData();
    }
  },
};
</script>
<style lang="less" scoped>
@import '@/style/variables.less';

.list-common-table {
  background-color: var(--td-bg-color-container);
  padding: 30px 32px;
  border-radius: var(--td-radius-default);
}

.form-item-content {
  width: 100%;
}

.operation-container {
  display: flex;
  justify-content: flex-end;
  align-items: center;
}

.payment-col {
  display: flex;

  .trend-container {
    display: flex;
    align-items: center;
    margin-left: 8px;
  }
}

.t-button+.t-button {
  margin-left: var(--td-comp-margin-s);
}
</style>
