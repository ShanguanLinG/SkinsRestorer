# SkinsRestorer 开发工作流

## 目录

- [构建命令](#构建命令)
- [测试命令](#测试命令)
- [检查与格式化](#检查与格式化)
- [代码规范](#代码规范)
  - [语言与格式](#语言与格式)
  - [导入](#导入)
  - [命名](#命名)
  - [Lombok 使用](#lombok-使用)
  - [错误处理](#错误处理)
  - [测试](#测试)

## 构建命令

- 完整构建：`./gradlew build`
- 跳过测试构建：`./gradlew build -x test`
- 清理后构建：`./gradlew clean build`
- 仅编译：`./gradlew compileJava`

## 测试命令

- 全部测试：`./gradlew test`
- 单个测试类：`./gradlew test --tests "ClassName"`
- 单个测试方法：`./gradlew test --tests "ClassName.methodName"`
- 测试与覆盖率报告：`./gradlew test jacocoTestReport`

## 检查与格式化

- 检查格式：`./gradlew spotlessCheck`
- 应用格式：`./gradlew spotlessApply`
- 静态分析：`./gradlew compileJava`, 已启用 ErrorProne。

## 代码规范

### 语言与格式

- 使用 Java 25。
- 缩进使用 4 个空格, 不使用制表符。
- 每行最多 120 个字符。
- 编码使用 UTF-8。
- 所有 Java 文件必须包含许可证头, 由 Spotless 自动添加。

### 导入

- 按字母顺序排列导入。
- `java.*` 导入与其他导入之间保留空行。
- 谨慎使用静态导入, 仅用于常量。

### 命名

- 类使用 PascalCase。
- 方法和变量使用 camelCase。
- 常量使用 UPPER_SNAKE_CASE。
- 包名使用小写字母并以点分隔。

### Lombok 使用

- 使用 Lombok 注解, 如 `@Data`、`@AllArgsConstructor`。
- 启用 JetBrains 空值注解：`lombok.addNullAnnotations = jetbrains`。
- 优先使用构造器注入, 避免字段注入。

### 错误处理

- 可恢复错误使用受检异常。
- 编程错误使用运行时异常。
- 使用 SRLogger 适当记录错误。
- 使用 `Objects.requireNonNull()` 校验输入。

### 测试

- 单元测试使用 JUnit 5 和 Mockito。
- 集成测试使用 TestContainers。
- 单元测试文件使用 `*Test.java` 命名。
- 使用 SRExtension 提供公共测试初始化。
