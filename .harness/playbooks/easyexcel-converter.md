# Playbook: EasyExcel 自定义 Converter 开发

> 适用于所有使用 EasyExcel (Alibaba) 的 Java 项目。

---

## 适用场景

- 使用 EasyExcel 导入时需要自定义类型转换（BigDecimal、Long、Date 等）
- 导入文件单元格格式不统一（数字列混入文本、空白单元格、公式单元格）
- 需要对 sheet 表头做校验/匹配

---

## 确认的问题与解决方案

### 问题 1: supportExcelTypeKey 返回值导致 Converter 不生效

**现象**：自定义 Converter 写好了但运行时不走自定义逻辑，仍然抛 `ExcelDataConvertException`。

**根因**：EasyExcel 3.x 使用精确匹配机制——`supportExcelTypeKey()` 返回的 `CellDataTypeEnum` 必须与实际单元格类型完全一致。

**处理**：为所有可能的单元格类型注册同一个 Converter：
```java
private void registerFlexibleConverters(ExcelReaderBuilder builder) {
    for (CellDataTypeEnum type : CellDataTypeEnum.values()) {
        builder.registerConverter(new FlexibleBigDecimalConverter(type));
        builder.registerConverter(new FlexibleLongConverter(type));
    }
}
```

**复用建议**：任何自定义 Converter 都应该用此模式注册所有 CellDataTypeEnum。

---

### 问题 2: 空值/异常类型单元格导致 NPE

**现象**：空白单元格、布尔值单元格、错误公式单元格时 Converter 抛异常。

**根因**：未对 `cellData.getType()` 为 null/EMPTY/BOOLEAN/ERROR 做防御。

**处理**：
```java
@Override
public BigDecimal convertToJavaData(ReadCellData<?> cellData, ...) {
    if (cellData == null) return null;
    CellDataTypeEnum type = cellData.getType();
    if (type == null || type == CellDataTypeEnum.EMPTY
            || type == CellDataTypeEnum.BOOLEAN
            || type == CellDataTypeEnum.ERROR) {
        return null;
    }
    // ... 正常转换
}
```

**复用建议**：所有自定义 Converter 开头必须加这段防御代码。

---

### 问题 3: 数值转换失败应返回 null 而非抛异常

**现象**：单元格内容为 "N/A"、"-"、空格时，`new BigDecimal(str)` 抛 NumberFormatException。

**处理**：
```java
try {
    return new BigDecimal(stringValue.trim());
} catch (NumberFormatException e) {
    return null;
}
```

---

### 问题 4: 表头校验不应整包加载 Workbook

**现象**：用 `WorkbookFactory.create(inputStream)` 做表头校验，大文件 OOM。

**处理**：改用 EasyExcel 流式读取，只探测目标 sheet 的前 N 行后主动停止。

---

## 经验总结

| # | 原则 |
|---|------|
| 1 | Converter 必须注册所有 CellDataTypeEnum |
| 2 | convertToJavaData 开头必须防御 null/EMPTY/BOOLEAN/ERROR |
| 3 | 数值转换必须 catch NumberFormatException |
| 4 | 表头校验用流式探测，不用 WorkbookFactory |
| 5 | 一个 Converter 类通过构造函数接收 type 参数复用 |
