# Login Sample - Authentication Feature

## 概述

这个示例展示了如何使用 MVI 架构实现一个简单的认证（登录/登出）功能。

## 目录结构

```
login/
├── LoginContract.kt    - MVI 契约定义（State, Event, Intent, PartialChange）
├── LoginViewModel.kt   - 业务逻辑处理
└── LoginFragment.kt    - UI 层实现
```

## 核心概念

### 1. State（状态）

```kotlin
data class State(
    val isLoggedIn: Boolean = false,      // 是否已登录
    val username: String = "",            // 当前用户名
    val isLoading: Boolean = false,       // 是否正在加载
    val errorMessage: String? = null,     // 错误信息
)
```

**计算属性（Computed Properties）：**
- `isLoginEnabled`: 登录按钮是否可用
- `isLogoutEnabled`: 登出按钮是否可用
- `statusText`: 状态显示文本

### 2. Event（一次性事件）

```kotlin
sealed interface Event : Mvi.Event {
    data class ShowToast(val message: String) : Event
    data object NavigateToHome : Event
}
```

**事件类型：**
- `ShowToast`: 显示提示消息
- `NavigateToHome`: 导航到主页

### 3. Intent（用户意图）

```kotlin
sealed interface Intent : Mvi.Intent.Sequential {
    data class Login(val username: String, val password: String) : Intent
    data object Logout : Intent
    data object ClearError : Intent
}
```

**意图类型：**
- `Login`: 登录操作（带用户名和密码）
- `Logout`: 登出操作
- `ClearError`: 清除错误消息

### 4. PartialChange（状态转换函数）

使用函数式接口定义状态转换：

```kotlin
fun interface PartialChange : Mvi.PartialChange<State, Event>
```

## 功能特性

### 1. 表单验证

- **空值验证**: 用户名和密码不能为空
- **长度验证**: 密码必须至少 6 个字符
- **实时错误清除**: 用户输入时自动清除错误消息

### 2. 异步登录流程

```kotlin
private fun handleLogin(intent: Intent.Login): Flow<PartialChange> = flow {
    // 1. 验证输入
    if (intent.username.isBlank() || intent.password.isBlank()) {
        emit(PartialChange { /* 更新错误状态 */ })
        return@flow
    }
    
    try {
        // 2. 显示加载状态
        emit(PartialChange { /* 设置 isLoading = true */ })
        
        // 3. 模拟异步认证
        randomDelay()
        
        // 4. 密码验证
        if (intent.password.length < 6) {
            emit(PartialChange { /* 密码太短错误 */ })
            return@flow
        }
        
        // 5. 登录成功
        emit(PartialChange { /* 更新登录状态 + NavigateToHome 事件 */ })
        emit(PartialChange { /* ShowToast 欢迎消息 */ })
        
    } catch (e: Exception) {
        // 6. 错误处理
        emit(PartialChange { /* 显示错误 */ })
    } finally {
        // 7. 总是关闭加载状态
        emit(PartialChange { /* 设置 isLoading = false */ })
    }
}
```

### 3. 状态管理

**按钮状态：**
- 登录按钮：未登录且不在加载时启用
- 登出按钮：已登录且不在加载时启用

**UI 可见性：**
- 登录表单：登录后隐藏
- 错误消息：有错误时显示

### 4. 事件处理

**Toast 消息：**
- 成功登录提示
- 成功登出提示
- 错误提示

**导航：**
- 登录成功后触发 `NavigateToHome` 事件

## UI 交互流程

### 登录流程
```
用户输入 → Login Intent → ViewModel
    ↓
验证输入 → 显示加载 → 异步认证 → 更新状态
    ↓
UI 更新 ← State 变化 ← PartialChange
    ↓
Toast/导航 ← Event 触发
```

### 登出流程
```
点击登出 → Logout Intent → ViewModel
    ↓
显示加载 → 清除会话 → 重置状态
    ↓
UI 更新 ← State 变化 ← PartialChange
```

## 代码亮点

### 1. 计算属性分离业务逻辑与 UI 逻辑

```kotlin
val isLoginEnabled: Boolean
    get() = !isLoading && !isLoggedIn
```

好处：
- ViewModel 不需要处理 UI 状态逻辑
- UI 直接绑定计算属性
- 逻辑集中在 State，便于测试

### 2. Flow 处理复杂异步操作

```kotlin
private fun handleLogin(intent: Intent.Login): Flow<PartialChange>
```

好处：
- 支持多次状态发射（loading → success/error → complete）
- 自动取消支持
- 清晰的 try-catch-finally 错误处理

### 3. 响应式 Intent 收集

```kotlin
private val intents: Flow<Intent>
    get() = merge(
        binding.loginButton.doOnClick { /* 创建 Login Intent */ }.debounceFirst(600L),
        binding.logoutButton.doOnClick { /* 创建 Logout Intent */ }.debounceFirst(600L),
    )
```

好处：
- 声明式 UI 绑定
- 自动防抖处理
- 统一的 Intent 流

### 4. 生命周期感知的状态和事件收集

```kotlin
viewModel.stateFlow.collectState(viewLifecycleOwner) {
    collectPartial(State::statusText, binding.statusText::setText)
    collectPartial(State::isLoading, binding.loadingBar::isVisible::set)
}

viewModel.eventFlow.collectEvent(this) {
    collectParticular<Event.ShowToast> { event ->
        context?.showToast(event.message)
    }
}
```

好处：
- 自动生命周期管理
- 只在属性变化时更新 UI
- 类型安全的事件处理

## 运行示例

1. 启动应用
2. 点击 "Login Sample" 按钮
3. 输入用户名和密码（密码至少 6 个字符）
4. 点击 "Login" 按钮
5. 查看登录状态变化
6. 点击 "Logout" 按钮退出

## 扩展思路

### 简单扩展：
1. 添加"记住我"功能
2. 添加"忘记密码"流程
3. 保存登录状态到 SharedPreferences

### 进阶扩展：
1. 集成真实的网络 API
2. 添加 Token 管理
3. 实现自动登录
4. 添加生物识别认证

## 与 Counter 示例的对比

| 特性 | Counter | Login |
|-----|---------|-------|
| 状态复杂度 | 简单（3 个字段） | 中等（4 个字段） |
| 异步操作 | Reset 操作 | Login/Logout 操作 |
| 表单处理 | 无 | 有（输入验证） |
| 错误处理 | 简单（Toast） | 复杂（验证 + 显示） |
| 导航事件 | 无 | 有（NavigateToHome） |
| 计算属性 | 多个（UI 展示） | 多个（按钮状态） |

## 总结

这个示例展示了 MVI 架构在处理认证功能时的优势：

1. **清晰的数据流**: Intent → ViewModel → State → UI
2. **易于测试**: 业务逻辑与 UI 完全分离
3. **类型安全**: 编译时检查所有状态和事件
4. **响应式编程**: 声明式 UI 更新
5. **易于维护**: 单向数据流，状态可预测

这个模式可以扩展到更复杂的认证场景，如 OAuth、SSO 等。

