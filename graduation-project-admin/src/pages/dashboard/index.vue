<template>
  <div>
    <t-back-top
      container=".tdesign-starter-layout"
      :visible-height="0"
      style="position: absolute"
      :offset="['24px', '80px']"
    >
      <t-icon name="backtop" size="20px" />
    </t-back-top>
  </div>
</template>
<script>

export default {
  name: 'DashboardBase',
  components: {
  },
};
</script>
<style scoped lang="less">
.row-container {
  margin-bottom: 16px;
}
/deep/ .t-card__body {
  padding-top: 0;
}
</style>
