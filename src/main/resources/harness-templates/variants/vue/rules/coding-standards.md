# 编码规范 — Vue 项目

> 本文件定义 Vue 前端项目的编码规范和架构约束。

---

## 项目结构

```
src/
├── api/            # 接口定义（按模块分文件）
├── assets/         # 静态资源
├── components/     # 公共组件
├── views/          # 页面级组件（路由对应）
├── router/         # 路由配置
├── store/          # 状态管理（Vuex/Pinia）
├── utils/          # 工具函数
├── mixins/         # 混入（Vue 2）/ composables（Vue 3）
└── styles/         # 全局样式
```

---

## 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 组件文件 | PascalCase | `UserProfile.vue` |
| 页面文件 | PascalCase 或 kebab-case | `UserList.vue` |
| JS/TS 工具 | camelCase | `formatDate.js` |
| CSS 类名 | BEM 或 kebab-case | `.user-card__title` |
| 事件名 | kebab-case | `@update-status` |
| Props | camelCase（模板中 kebab-case） | `:userName` / `user-name` |

---

## 组件规范

```vue
<!-- 单文件组件顺序 -->
<template>...</template>
<script>...</script>
<style scoped>...</style>
```

- Props 必须定义类型和默认值
- 事件用 `$emit` 向上通信，不直接修改父组件数据
- 组件职责单一，超过 300 行考虑拆分
- 列表渲染必须加 `:key`

---

## API 调用规范

```javascript
// ✓ 统一在 api/ 目录定义
// api/user.js
export function getUserList(params) {
  return request({ url: '/user/list', method: 'get', params })
}

// ✗ 在组件中直接写 axios 调用
```

---

## 状态管理

- 全局状态用 Vuex/Pinia，局部状态用组件 data/ref
- 不在组件中直接修改 store state（必须通过 mutation/action）
- 异步操作放在 actions 中

---

## 样式规范

- 组件样式使用 `<style scoped>`
- 全局样式放 `styles/` 目录
- 使用 CSS 变量或预处理器变量管理主题色
- Element UI / Ant Design 组件样式覆盖放统一文件

---

## 禁止行为

- ✗ 在 `created`/`mounted` 中写大量业务逻辑（提取为方法）
- ✗ 深层嵌套的 `v-if`/`v-for`（超过 3 层需重构）
- ✗ 在模板中写复杂表达式（用 computed）
- ✗ 不处理接口错误（必须 catch 并提示用户）
- ✗ 硬编码接口地址（用环境变量）
- ✗ 在 `v-for` 中使用 index 作为 key（有唯一 ID 时）
