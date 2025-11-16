# State::countText 变更文档更新

## 📝 变更内容

### 原来的实现
```kotlin
data class State(val count: Int = 0) : Mvi.State

// UI 层需要手动转换
viewModel.stateFlow.collectState(viewLifecycleOwner) {
    collectPartial(State::count) { count ->
        binding.count.text = count.toString()  // 手动转换为 String
    }
}
```

### 新的实现（使用计算属性）
```kotlin
data class State(val count: Int = 0) : Mvi.State {
    val countText: String
        get() = count.toString()
}

// UI 层直接使用方法引用
viewModel.stateFlow.collectState(viewLifecycleOwner) {
    collectPartial(State::countText, binding.count::setText)  // 更简洁、类型安全
}
```

## ✅ 已更新的文档

### 1. CounterContract.kt
- ✅ 添加了 `countText` 计算属性的详细文档
- ✅ 说明了分离业务逻辑和展示逻辑的设计理念
- ✅ 列举了使用计算属性的三个好处

### 2. CounterFragment.kt
- ✅ 更新类文档注释：`State::count` → `State::countText`
- ✅ 更新 `setupView()` 方法注释，详细说明计算属性的优势
- ✅ 添加了第4个好处：计算属性提供展示逻辑分离

### 3. COUNTER_SAMPLE_GUIDE.md
- ✅ 更新 Contract 层示例代码，包含 `countText` 计算属性
- ✅ 添加 "Design Pattern - Computed Properties" 章节
- ✅ 更新 "Efficient State Observation" 示例使用方法引用
- ✅ 更新 "Comparison" 章节展示新的用法
- ✅ 在 Benefits 列表中添加计算属性和方法引用的优势

### 4. 更新总结.md
- ✅ 更新代码示例使用 `State::countText` 和方法引用
- ✅ 添加 "2.1. 计算属性分离展示逻辑" 章节
- ✅ 说明了计算属性的三个优势

## 🎯 设计优势

### 分离关注点
- **业务逻辑**: `count: Int` - 领域模型数据
- **展示逻辑**: `countText: String` - UI 格式化

### 更简洁的 UI 绑定
**之前**:
```kotlin
collectPartial(State::count) { count ->
    binding.count.text = count.toString()  // Lambda + 手动转换
}
```

**现在**:
```kotlin
collectPartial(State::countText, binding.count::setText)  // 方法引用，类型安全
```

### 优势对比

| 特性 | 原来的方式 | 新方式（计算属性） |
|------|-----------|------------------|
| **代码行数** | 3 行 (lambda) | 1 行 (方法引用) |
| **类型安全** | 运行时 | 编译时 |
| **格式化位置** | UI 层分散 | State 集中 |
| **可复用性** | 每处重复 | 一次定义多处使用 |
| **可测试性** | 需要 UI 测试 | 可单独单元测试 |
| **可维护性** | 改格式需改多处 | 改一处即可 |

## 💡 扩展示例

如果需要更复杂的格式化，只需修改 State：

```kotlin
data class State(val count: Int = 0) : Mvi.State {
    // 简单格式
    val countText: String
        get() = count.toString()
    
    // 带前缀的格式（无需修改 UI 层代码）
    val countTextWithLabel: String
        get() = "Count: $count"
    
    // 格式化数字（无需修改 UI 层代码）
    val countTextFormatted: String
        get() = "%03d".format(count)
    
    // 百分比格式（无需修改 UI 层代码）
    val countPercentage: String
        get() = "$count%"
}
```

UI 层只需要选择使用哪个属性：
```kotlin
// 使用不同的格式化，无需改 lambda
collectPartial(State::countText, binding.count::setText)
collectPartial(State::countTextFormatted, binding.count::setText)
collectPartial(State::countPercentage, binding.count::setText)
```

## 📚 相关最佳实践

### 1. 计算属性 vs 状态字段
- ✅ **使用计算属性**: 可以从其他字段派生的值（如格式化、转换）
- ❌ **不要用计算属性**: 独立的状态数据

### 2. 保持计算属性简单
```kotlin
// ✅ 简单转换
val countText: String get() = count.toString()

// ❌ 复杂逻辑应该在 ViewModel 中
val complexCalculation: String 
    get() = performExpensiveCalculation(count) // 不推荐
```

### 3. 方法引用的使用
```kotlin
// ✅ 类型匹配时使用方法引用
collectPartial(State::countText, binding.count::setText)

// ✅ 需要转换时使用 lambda
collectPartial(State::isEnabled) { enabled ->
    binding.button.isEnabled = enabled
}
```

## 🎉 总结

通过引入 `State::countText` 计算属性：

1. ✅ 代码更简洁（方法引用 vs lambda）
2. ✅ 更好的关注点分离（State 负责格式化）
3. ✅ 更容易维护（格式化逻辑集中）
4. ✅ 更好的类型安全（编译时检查）
5. ✅ 更容易测试（State 的计算属性可单独测试）
6. ✅ 更好的可复用性（多个 UI 组件可共享）

这是一个很好的 MVI 最佳实践示例！

---

**日期**: 2025-11-14  
**变更类型**: 功能增强 + 文档更新  
**影响范围**: CounterContract, CounterFragment, 文档

