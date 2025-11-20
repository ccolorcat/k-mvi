# 双重实现模式（Dual Implementation Pattern）

## 设计模式说明

在 Login 模块中，我们使用了**双重实现模式**，让某些类型同时实现多个接口。

## 模式示例

### 1. ClearError：Intent + PartialChange

```kotlin
data object ClearError : Intent, PartialChange
```

**设计理由**：
- `ClearError` 是一个非常简单的操作，只需要清空错误消息
- 不需要额外的业务逻辑或异步处理
- Intent 和 PartialChange 是一对一的映射关系

**优势**：
- ✅ **简化 ViewModel**：不需要单独的 handler 方法
- ✅ **代码更少**：直接使用 `asSingleFlow()` 即可
- ✅ **意图明确**：Intent 即是 PartialChange，关系一目了然

**使用示例**：
```kotlin
// ViewModel 中
private fun dispatchIntent(intent: Intent): Flow<PartialChange> {
    return when (intent) {
        is Intent.Login -> handleLogin(intent)
        is Intent.Logout -> handleLogout()
        is Intent.ClearError -> ClearError.asSingleFlow()  // 直接转换！
    }
}

// Fragment 中
viewModel.dispatch(Intent.ClearError)  // 作为 Intent 发送
```

### 2. ShowToast：Event + PartialChange

```kotlin
data class ShowToast(val message: String) : Event, PartialChange
```

**设计理由**：
- `ShowToast` 是一个纯副作用事件，不需要修改状态
- 可以独立使用，也可以与状态更新组合

**优势**：
- ✅ **灵活性**：可单独发送，也可嵌入其他 PartialChange
- ✅ **减少包装**：不需要额外的 PartialChange 包装类
- ✅ **可重用**：在多个地方使用同一个 Event

**使用示例**：
```kotlin
// 独立使用（仅事件，无状态变化）
emit(ShowToast("Operation completed"))

// 组合使用（事件 + 状态变化）
is CompleteLogin -> old.updateWith(ShowToast("Welcome, $username!")) {
    copy(isLoggedIn = true, username = username, errorMessage = "")
}
```

## 何时使用这个模式？

### ✅ 适合使用的场景

| 类型组合 | 场景 | 示例 |
|---------|------|------|
| **Intent + PartialChange** | 简单的同步操作 | `ClearError`, `Reset`, `ToggleSetting` |
| **Event + PartialChange** | 纯副作用事件 | `ShowToast`, `ShowSnackbar`, `Vibrate` |

**判断标准**：
1. **无业务逻辑**：不需要验证、计算或异步操作
2. **一对一映射**：Intent 直接对应一个状态变化
3. **简单转换**：状态变化是直接的字段赋值

### ❌ 不适合使用的场景

| 场景 | 原因 | 推荐做法 |
|-----|------|----------|
| 需要异步操作 | 需要返回 `Flow<PartialChange>` | 使用单独的 handler |
| 需要验证逻辑 | 需要早返回或条件判断 | 使用单独的 handler |
| 复杂的状态转换 | 涉及多个状态字段的复杂计算 | 使用单独的 handler |
| 需要访问外部依赖 | 需要 Repository、API 等 | 使用单独的 handler |

## 对比分析

### 方案 1：传统方式（分离 Intent 和 PartialChange）

```kotlin
sealed interface Intent : Mvi.Intent.Sequential {
    data object ClearError : Intent
}

sealed interface PartialChange : Mvi.PartialChange<State, Event> {
    data object ClearErrorChange : PartialChange
}

// ViewModel 中需要映射
private fun handleClearError(intent: Intent.ClearError): PartialChange {
    return PartialChange.ClearErrorChange
}
```

**缺点**：
- ❌ 需要两个类型定义
- ❌ 需要一个 handler 方法
- ❌ 代码冗余

### 方案 2：双重实现（本项目采用）

```kotlin
data object ClearError : Intent, PartialChange

// ViewModel 中直接使用
is Intent.ClearError -> ClearError.asSingleFlow()
```

**优点**：
- ✅ 一个类型定义
- ✅ 无需 handler 方法
- ✅ 代码简洁

## 实际效果对比

### Login 模块中的应用

| Intent | 是否双重实现 | 处理方式 | 原因 |
|--------|-------------|----------|------|
| `Login` | ❌ | `handleLogin()` | 需要验证 + 异步操作 |
| `Logout` | ❌ | `handleLogout()` | 需要异步操作 + 错误模拟 |
| `ClearError` | ✅ | `asSingleFlow()` | 简单的同步状态清除 |

**代码对比**：

```kotlin
// 复杂操作（不使用双重实现）
is Intent.Login -> handleLogin(intent)  // 需要专门的方法

private fun handleLogin(intent: Intent.Login): Flow<PartialChange> = flow {
    // 验证
    if (intent.username.isBlank() || intent.password.isBlank()) {
        emit(SetErrorMessage("Username and password cannot be empty"))
        return@flow
    }
    
    try {
        emit(StartLoading)
        randomDelay()
        // ... 复杂逻辑
        emit(CompleteLogin(intent.username))
    } catch (e: Exception) {
        emit(FailLogin("Login failed: ${e.message}"))
    } finally {
        emit(StopLoading)
    }
}

// 简单操作（使用双重实现）
is Intent.ClearError -> ClearError.asSingleFlow()  // 一行搞定！
```

## Counter vs Login 对比

### Counter 示例的做法

Counter 中所有 PartialChange 都在 ViewModel 中内联创建：

```kotlin
private fun handleIncrement(intent: Intent.Increment): PartialChange {
    return PartialChange {
        if (it.state.count == COUNT_MAX) {
            it.withEvent(Event.ShowToast("Already reached $COUNT_MAX"))
        } else {
            it.updateState { copy(count = count + 1) }
        }
    }
}
```

### Login 示例的做法

Login 中将 PartialChange 定义在 Contract 中，简单操作使用双重实现：

```kotlin
// Contract 中定义
sealed interface PartialChange {
    // 复杂操作：单独定义
    data class CompleteLogin(val username: String) : PartialChange
    
    // 简单操作：双重实现
    data object ClearError : Intent, PartialChange
}

// ViewModel 中使用
is Intent.ClearError -> ClearError.asSingleFlow()  // 简洁！
```

## 设计原则总结

### 核心思想

> **"Simple things should be simple, complex things should be possible."**
> 
> 简单的事情应该简单实现，复杂的事情应该能够实现。

### 实践建议

1. **先评估复杂度**：判断 Intent 是简单还是复杂
2. **简单用双重实现**：无业务逻辑 → Intent + PartialChange
3. **复杂用单独 handler**：有业务逻辑 → 单独的 handler 方法
4. **保持一致性**：团队内统一判断标准

### 好处总结

| 好处 | 说明 |
|-----|------|
| **减少样板代码** | 简单操作不需要额外的 handler |
| **提高可读性** | Intent 和 PartialChange 的对应关系一目了然 |
| **保持灵活性** | 复杂操作仍然可以使用传统方式 |
| **易于维护** | 减少了需要维护的代码量 |

## 示例代码

### 完整的 ClearError 实现

```kotlin
// LoginContract.kt
sealed interface LoginContract {
    sealed interface Intent : Mvi.Intent.Sequential {
        data class Login(val username: String, val password: String) : Intent
        data object Logout : Intent
        // 注意：ClearError 在底部定义
    }
    
    sealed interface PartialChange : Mvi.PartialChange<State, Event> {
        override fun apply(old: Snapshot): Snapshot {
            return when (this) {
                is ClearError -> old.updateState { copy(errorMessage = "") }
                // ... 其他 case
            }
        }
        
        // 注意：这里没有单独定义 ClearError
    }
    
    // 双重实现定义在这里
    data object ClearError : Intent, PartialChange
}

// LoginViewModel.kt
class LoginViewModel : ViewModel() {
    private fun dispatchIntent(intent: Intent): Flow<PartialChange> {
        return when (intent) {
            is Intent.Login -> handleLogin(intent)
            is Intent.Logout -> handleLogout()
            is Intent.ClearError -> ClearError.asSingleFlow()  // 简洁！
        }
    }
}

// LoginFragment.kt
class LoginFragment : Fragment() {
    private fun setupViews() {
        binding.usernameInput.doOnTextChanged { _, _, _, _ ->
            viewModel.dispatch(Intent.ClearError)  // 作为 Intent 使用
        }
    }
}
```

## 总结

双重实现模式是一种在特定场景下非常有用的设计模式：

✅ **简化简单操作**  
✅ **保持代码清晰**  
✅ **减少样板代码**  
✅ **不影响复杂操作的实现**  

这是 Login 示例展示的一个重要设计技巧，与 Counter 示例形成互补，为开发者提供了更多的实现选择。

