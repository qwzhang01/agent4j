<template>
  <t-card :bordered="false">
    <t-form ref="form" :data="formData" :rules="FORM_RULES" label-align="top" :label-width="100" @reset="onReset"
      @submit="onSubmit">
      <div class="form-basic-container">
        <div class="form-basic-item">
          <div class="form-basic-container-title form-title-gap">消息通知</div>
          <t-form-item label="标题" name="title">
            <t-input v-model="formData.title" placeholder="请输入消息通知内容" />
          </t-form-item>
          <t-form-item label="内容" name="content">
            <t-textarea v-model="formData.content" :height="124" placeholder="请输入消息通知内容" />
          </t-form-item>
        </div>
      </div>

      <div class="form-submit-container">
        <div class="form-submit-sub">
          <div class="form-submit-left">
            <t-button theme="primary" class="form-submit-confirm" type="submit"> 提交 </t-button>
            <t-button type="reset" class="form-submit-cancel" theme="default" variant="base"> 取消 </t-button>
          </div>
        </div>
      </div>
    </t-form>
  </t-card>
</template>
<script>
import { prefix } from '@/config/global';

const INITIAL_DATA = {
  content: '',
  title: '',
};
const FORM_RULES = {
  content: [{ required: true, message: '请输入消息通知内容', type: 'error' }],
  title: [{ required: true, message: '请输入消息通知标题', type: 'error' }],
};

export default {
  name: 'FormBase',
  data() {
    return {
      prefix,
      stepSuccess: true,
      formData: { ...INITIAL_DATA },
      FORM_RULES,
    };
  },
  methods: {
    onReset() {
      this.$router.go(-1);
    },
    onSubmit({ validateResult }) {
      if (validateResult === true) {
        this.$request.post('/api/oms/log/message/add', { title: this.formData.title, profile: this.formData.content }).then(res => {
          this.$message.success('保存成功')
          this.$router.go(-1);
        })
      }
    }
  }
}
</script>
<style lang="less" scoped>
@import '@/style/variables.less';

.form-basic-container {
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--td-bg-color-container);
  padding: 0 32px 134px;

  @media (max-width: @screen-sm-max) {
    padding: 0 32px 67px;

    .form-basic-container-title {
      margin: 32px 0 32px;
    }
  }

  .form-basic-item {
    width: 676px;

    .form-basic-container-title {
      font-size: 20px;
      line-height: 24px;
      color: var(--td-text-color-primary);
      margin: var(--td-comp-size-xxxl) 0 32px;
    }
  }
}

.form-submit-container {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding-top: 30px;
  padding-bottom: 28px;
  background-color: var(--td-bg-color-component);
  border-bottom-left-radius: 3px;
  border-bottom-right-radius: 3px;

  .form-submit-sub {
    width: 676px;
    display: flex;
    align-items: center;
    justify-content: space-between;

    .form-submit-left {
      .form-submit-upload-span {
        font-size: 14px;
        line-height: 22px;
        color: var(--td-text-color-placeholder);
        padding-top: 8px;
        display: inline-block;
      }
    }
  }
}
</style>
