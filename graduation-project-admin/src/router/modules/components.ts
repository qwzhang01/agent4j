import { ViewModuleIcon, Edit1Icon, LayersIcon } from 'tdesign-icons-vue';
import Layout from '@/layouts/index.vue';

export default [
  {
    path: '/client',
    name: 'Client',
    component: Layout,
    redirect: '/client/list',
    meta: { title: '用户管理', icon: ViewModuleIcon },
    children: [
      {
        path: 'list',
        name: 'ClientList',
        component: () => import('@/pages/client/index.vue'),
        meta: { title: '用户管理', roleCode: 'vip' },
      }
    ],
  }, {
    path: '/msg',
    name: 'Msg',
    component: Layout,
    redirect: '/msg/list',
    meta: { title: '通知管理', icon: ViewModuleIcon },
    children: [
      {
        path: 'list',
        name: 'MsgList',
        component: () => import('@/pages/message/index.vue'),
        meta: { title: '通知管理', roleCode: 'vip' },
      },{
        path: 'form',
        name: 'MsgForm',
        component: () => import('@/pages/message/form.vue'),
        meta: { title: '通知管理', roleCode: 'vip' , hidden: true},
      }
    ],
  }, {
    path: '/system',
    name: 'System',
    component: Layout,
    redirect: '/system/user',
    meta: { title: '系统配置', icon: ViewModuleIcon },
    children: [
      {
        path: 'user',
        name: 'User',
        component: () => import('@/pages/system/user/index.vue'),
        meta: { title: '用户管理', roleCode: 'vip' },
      }, {
        path: 'role',
        name: 'Role',
        component: () => import('@/pages/system/role/index.vue'),
        meta: { title: '角色管理', roleCode: 'vip' },
      }, {
        path: 'dict',
        name: 'Dict',
        component: () => import('@/pages/system/dict/index.vue'),
        meta: { title: '字典管理', roleCode: 'vip' },
      }, {
        path: 'action',
        name: 'Action',
        component: () => import('@/pages/system/action/index.vue'),
        meta: { title: '操作日志', roleCode: 'vip' },
      },
    ],
  },
];
