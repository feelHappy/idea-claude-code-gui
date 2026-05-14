# 编码规范 — Spring Boot 项目

> 本文件定义 Spring Boot 项目的编码规范和架构约束。
> Agent 在编码实现阶段（⑤）和代码审查阶段（⑥）必须加载本文件。

---

## 分层架构

```
Controller → Service → Mapper/Repository → Entity
                ↕
            DTO / VO / Convertor
```

| 层 | 职责 | 命名规范 |
|----|------|---------|
| Controller | 接收请求、参数校验、调用 Service | `XxxController` |
| Service | 业务逻辑编排 | `XxxService` / `XxxServiceImpl` |
| Mapper | 数据访问（MyBatis/JPA） | `XxxMapper` / `XxxRepository` |
| Entity | 数据库实体映射 | `XxxEntity` 或 `Xxx` |
| DTO | 数据传输对象（跨层/跨服务） | `XxxDTO` |
| VO | 视图对象（返回给前端） | `XxxVO` |
| Convertor | 对象转换（Entity↔DTO↔VO） | `XxxConvertor` |

---

## 关键约束

| 类别 | 规范 |
|------|------|
| 金额字段 | `BigDecimal`，运算时指定 `RoundingMode.HALF_UP` |
| ID 生成 | 雪花 ID `@TableId(type = IdType.ASSIGN_ID)` 或自增 |
| 软删除 | `@TableLogic` on `delFlag` 字段 |
| 审计字段 | `createdBy` / `createdTime` / `updatedBy` / `updatedTime`，使用 `FieldFill` 自动填充 |
| 外部调用 | 必须设超时（connectTimeout + readTimeout）和降级 |
| SQL 参数 | `#{}` 不用 `${}`（防注入） |
| 事务 | `@Transactional` 只加在 Service 层，不加在 Controller |
| 异常处理 | 业务异常用 `BusinessException`，不吞异常 |

---

## MyBatis-Plus 规范（如使用）

```java
// ✓ Lambda 查询
LambdaQueryWrapper<Entity> wrapper = new LambdaQueryWrapper<>();
wrapper.eq(Entity::getStatus, status)
       .orderByDesc(Entity::getCreatedTime);

// ✗ 字符串字段名
QueryWrapper<Entity> wrapper = new QueryWrapper<>();
wrapper.eq("status", status); // 重构时容易遗漏
```

- 分页：使用 `Page<T>` + `IPage<T>`
- 批量操作：确认最终走 JDBC batch（`executorType=BATCH`）
- 逻辑删除：查询自动过滤，物理删除需显式调用

---

## 响应格式

```java
// 统一响应包装
R<T> {
    int code;       // 200=成功, 其他=失败
    String msg;     // 提示信息
    T data;         // 业务数据
}
```

---

## 禁止行为

- ✗ Controller 中写业务逻辑
- ✗ Service 中直接操作 HttpServletRequest/Response
- ✗ Entity 直接返回给前端（必须转 VO）
- ✗ 在循环中执行 SQL 查询（N+1 问题）
- ✗ 硬编码配置值（用 `@Value` 或配置类）
- ✗ catch Exception 后不处理（至少 log.error）
