<template>
    <div>
        <t-card class="list-card-container" :bordered="false">
            <t-row justify="space-between">
                <t-input v-model="pageQuery.content" @change.native.capture="loadList" class="search-input"
                    placeholder="请输入操作内容" clearable>
                    <template #suffix-icon>
                        <search-icon size="20px" />
                    </template>
                </t-input>
            </t-row>

            <div class="table-container">
                <t-table :columns="columns" :data="data" rowKey="id" :verticalAlign="verticalAlign" :hover="hover"
                    :pagination="pagination" :loading="dataLoading" @change="pageChange" :headerAffixedTop="true"
                    :headerAffixProps="{ offsetTop: offsetTop, container: getContainer }">
                </t-table>
            </div>
        </t-card>
    </div>
</template>
<script lang="ts">
import { SearchIcon } from "tdesign-icons-vue";

export default {
    name: "ListBanner",
    components: {
        SearchIcon,
    },
    watch: {
        pageQuery: {
            handler(cul, old) { },
            deep: true,
        },
    },
    data() {
        return {
            dataLoading: false,
            data: [],
            columns: [
                {
                    title: "时间",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "actionTime",
                    fixed: "left",
                },
                {
                    title: "耗时",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "timeConsuming",
                    cell: (h, { row }) => {
                        if (row.timeConsuming) {
                            return row.timeConsuming + "毫秒";
                        }
                        return "";
                    },
                },
                {
                    title: "IP",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "clientIp",
                },
                {
                    title: "模块",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "module",
                },
                {
                    title: "地址",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "url",
                },
                {
                    title: "账户",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "account",
                },
                {
                    title: "描述",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "actionDesc",
                },
                {
                    title: "客户端信息",
                    align: "left",
                    width: 100,
                    ellipsis: true,
                    colKey: "userAgent",
                },
            ],
            tableLayout: "auto",
            verticalAlign: "top",
            hover: true,
            rowClassName: (rowKey: string) => `${rowKey}-class`,
            pageQuery: {
                content: "",
            },
            pagination: {
                pageSize: 10,
                total: 0,
                current: 1,
            },
        };
    },
    computed: {
        offsetTop() {
            return this.$store.state.setting.isUseTabsRouter ? 48 : 0;
        },
    },
    mounted() {
        this.loadList();
    },
    methods: {
        loadList() {
            this.dataLoading = true;

            const param = {
                ...this.pageQuery,
                current: this.pagination.current,
                size: this.pagination.pageSize,
            };

            this.$request.post('/api/oms/log/action/list', param)
                .then((res) => {
                    if (res) {
                        const { current, size, total, records } = res;
                        this.data = records;
                        this.pagination = {
                            total: parseInt(total),
                            current: parseInt(current),
                            pageSize: parseInt(size),
                        };
                    }
                })
                .finally(() => {
                    this.dataLoading = false;
                });
        },

        getContainer() {
            return document.querySelector(".tdesign-starter-layout");
        },
        pageChange(changeParams, triggerAndData) {
            const { pagination } = changeParams;
            const { current, pageSize } = pagination;
            this.pagination = {
                ...this.pagination,
                current,
                pageSize,
            };
            this.loadList();
        },
    },
}
</script>

<style lang="less" scoped>
.payment-col {
    display: flex;

    .trend-container {
        display: flex;
        align-items: center;
        margin-left: 8px;
    }
}

.left-operation-container {
    padding: 0 0 6px 0;
    margin-bottom: 16px;

    .selected-count {
        display: inline-block;
        margin-left: var(--td-comp-margin-s);
        color: var(--td-text-color-secondary);
    }
}

.search-input {
    width: 360px;
}

.t-button+.t-button {
    margin-left: var(--td-comp-margin-s);
}
</style>