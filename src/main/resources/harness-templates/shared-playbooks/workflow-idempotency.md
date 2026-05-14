# Playbook: 工作流幂等性与状态机协调

> 适用于所有使用工作流引擎（eFlow/Activiti/Flowable/Camunda）的项目。

---

## 适用场景

- 工作流与业务状态需要双向同步
- 前端可能重复调用同一操作（作废、提交、回退）
- 流程监听器异步回写状态，与用户手动操作存在时序竞争

---

## 确认的问题与解决方案

### 问题 1: 终态操作重复调用时抛异常

**现象**：流程监听器异步回写了终态，前端的第二次请求到达时不满足前置条件，抛 BusinessException。

**根因**：只考虑了"正常态 → 终态"的单向转换，没有处理"已经是终态"的幂等场景。

**处理**：在状态校验逻辑中增加"已终态检测"分支：
```java
if (isAlreadyInTargetState(task)) {
    return handleIdempotent(task, param);
}
```

**复用建议**：任何与工作流联动的终态操作（作废、关闭、驳回）都必须设计幂等分支。

---

### 问题 2: 追加式字段更新时重复内容

**现象**：幂等处理时备注字段出现重复行。

**处理**：追加前逐行检查是否已存在相同内容：
```java
for (String line : originalRemark.split("\\R")) {
    if (message.equals(line.trim())) {
        return originalRemark; // 已存在，不重复追加
    }
}
```

---

### 问题 3: 覆盖操作中途失败无法回退

**现象**："先删后写"的覆盖模式，中途失败后状态已变更无法回退。

**处理**：
1. 操作前记录"回退点"状态
2. 删除+写入在同一事务中
3. 异常时显式回退到回退点

---

## 状态机设计模板

```java
public Result doOperation(Long taskId, String param) {
    Task task = getById(taskId);

    // 1. 幂等检查
    if (isAlreadyInTargetState(task)) {
        return handleIdempotent(task, param);
    }

    // 2. 前置条件检查
    if (!canTransition(task)) {
        throw new BusinessException("当前状态不允许此操作");
    }

    // 3. 乐观锁状态变更
    int affected = baseMapper.update(task,
        new LambdaUpdateWrapper<Task>()
            .eq(Task::getId, taskId)
            .eq(Task::getStatus, EXPECTED_STATUS));

    if (affected == 0) { /* 并发冲突处理 */ }

    return Result.ok(task);
}
```

---

## 经验总结

| # | 原则 |
|---|------|
| 1 | 终态操作必须幂等 |
| 2 | 监听器与业务接口存在时序竞争 |
| 3 | 追加式字段更新必须去重 |
| 4 | 覆盖操作必须有回退点 |
| 5 | 状态变更用乐观锁 |
