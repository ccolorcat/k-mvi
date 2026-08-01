# K-MVI core 审查观点合并稿（未复核）

生成日期：2026-08-01

## 合并说明

- 来源 R1：`docs/review_core_20260731.md`
- 来源 R2：`docs/review_core_20260731_omp.md`
- 本文只提取、归并两份文档中的观点，不审核观点、依据、行号、严重性或建议的正确性。
- 同一代码位置、相同问题成因及相同改进方向的观点只记录一次；若原文分类或严重性不同，则并列保留原始标注。
- 合并后共有 31 条问题/改进观点和 7 条正向观点。

## 问题与改进观点

### 运行时行为

#### 1. `groupHandle` 的已关闭通道竞态分支不会按注释重新建组

- 来源：R1 Bug 1（Medium）；R2 Bug 1（Medium）
- 位置：`core/src/main/java/cc/colorcat/mvi/internal/InternalExtensions.kt:296-300,312-315`，以及 `HandleStrategies.kt` 的相关 KDoc。
- 观点：`trySend` 返回 `isClosed` 后，代码调用 `group.channel.send(intent)`；向已关闭通道发送会抛出 `ClosedSendChannelException`，随后被外层捕获并重新抛出，导致整个 `groupHandle` flow 失败，而不是按注释所称重新打开 stale group。
- 影响：命中 `isClosedForSend` 检查与 `trySend` 之间的窄竞态时，当前意图可能丢失，所有分组及上游被取消，contract 最终变为 `Unavailable`。
- 建议：移除旧分组并通过 `openGroup(tag)` 重建通道后重发，或捕获 `ClosedSendChannelException` 并统一进入 reopen 路径；同时修正文档和补充竞态测试。

#### 2. `ReactiveContractLazy` 的非线程安全初始化可能创建多个 contract

- 来源：R1 Bug 2（Low）
- 位置：`MviViewModels.kt:243-245`、`internal/ReactiveContractImpl.kt:269-270`。
- 观点：委托使用 `lazy(LazyThreadSafetyMode.NONE, create)`，多线程并发首次访问时 initializer 可能执行多次；被丢弃的实例已经在 `viewModelScope` 中启动常驻 pipeline。KDoc 同时声称“只创建一次”和“并发时可能调用多次”，表述自相矛盾。
- 影响：标准主线程 ViewModel 场景下触发概率低；触发后可能短暂返回不同实例，并让被丢弃实例的通道和挂起协程存活到 ViewModel 清理。
- 建议：使用默认的同步 `lazy(create)`，并统一 KDoc 表述。

#### 3. `FatalErrorHandler` 正常返回时缺少运行时兜底

- 来源：R1 Bug 3（Medium）
- 位置：`internal/ReactiveContractImpl.kt:250-256`、`FatalErrorHandler.kt:6-8`。
- 观点：KDoc 要求实现不得正常返回，但核心 `catch` 在 `errorHandler.handle(cause)` 返回后没有继续抛错或记录错误。
- 影响：错误处理器意外返回时，snapshots pipeline 会静默完成；scope 仍 active，但后续 `dispatch` 永久返回 `Unavailable`。
- 建议：调用返回后抛出带原始 cause 的 `IllegalStateException`，或至少记录 ERROR，把文档约束转为运行时保证。

#### 4. `debounceLeading` 的参数校验延迟到 collect 阶段

- 来源：R1 Bug 4（Low）
- 位置：`MviExtensions.kt:138-139`。
- 观点：`require(timeMillis > 0L)` 位于 `flow {}` 内，非法参数在第一次收集时才抛出，而不是在调用点立即失败。
- 影响：配置错误可能延迟到生命周期收集阶段暴露，增加定位成本。
- 建议：把 `require` 移到 `flow {}` 外的函数体中。

#### 5. 生命周期收集器对缺失或已取消父 Job 的行为不明确

- 来源：R1 Bug 5（Low）
- 位置：`MviCollects.kt:133,347`。
- 观点：`SupervisorJob(owner.lifecycleScope.coroutineContext[Job])` 在父 Job 为 null 时会创建无父 Job，在父 Job 已取消时则创建已取消 Job。
- 影响：非标准 `LifecycleOwner` 下可能无法随生命周期清理，或收集静默失效，与 KDoc 的自动清理承诺不一致。
- 建议：通过 `requireNotNull` 快速失败，或在 KDoc 中明确对 `lifecycleScope` Job 的依赖。

### API 与设计

#### 6. free `collectTyped` 扩展的泛型约束过宽

- 来源：R1 Design 1（Medium）
- 位置：`MviCollects.kt:461-466`，对比 `MviCollects.kt:368`。
- 观点：`Flow<Mvi.Event>.collectTyped<E>()` 只约束 `E : Mvi.Event`，没有约束 E 必须是实际流元素类型的子类型；这弱于 `EventCollector.collectTyped` 的约束。
- 影响：可以编译出永远匹配不到事件的调用，错误会在运行期静默表现为无事件。
- 建议：采用双泛型约束，例如 `T : Mvi.Event, E : T`，将类型错误提前到编译期。

#### 7. HYBRID 分组数量没有硬上限或驱逐机制

- 来源：R1 Design 2（Info）
- 位置：`IntentTransformers.kt:184`、`HandleStrategies.kt:246-250`。
- 观点：每个新 tag 都会分配一个 Channel 和协程，`flattenMerge(Int.MAX_VALUE)` 也不限制组级并发；高基数 tag 会让资源占用线性增长。
- 影响：现有 warning threshold 只能诊断，不能阻止内存、通道和协程数量持续增加。
- 建议：考虑增加默认关闭的可选分组硬上限或驱逐策略；也可继续将其作为明确记录的设计权衡。

#### 8. `RENDEZVOUS` 与非 `SUSPEND` 溢出策略的组合会被静默忽略

- 来源：R1 Design 3（Info）
- 位置：`internal/InternalUtils.kt:23-30`。
- 观点：校验允许 `Channel.RENDEZVOUS` 搭配 `DROP_OLDEST` 等非 `SUSPEND` 策略，但底层 rendezvous channel 不提供相应缓冲丢弃语义，配置意图可能落空。
- 影响：用户以为配置了丢弃策略，实际仍得到纯 rendezvous 行为，且没有提示。
- 建议：要求 `RENDEZVOUS` 只能搭配 `SUSPEND`，或在 KDoc 中说明其他策略会被忽略。

#### 9. `CONFLATED` 通道的限制与丢意图语义说明不完整

- 来源：R1 Doc 5（Low）；R2 Design 1（Medium）
- 位置：`IntentQueueConfig.kt:22-25`、`HandleStrategies.kt:315-319`、`internal/InternalExtensions.kt:56-59`。
- 观点：入口队列文档没有说明 `Channel.CONFLATED` 要求 `onBufferOverflow == SUSPEND`；HYBRID 分组配置虽允许 `CONFLATED`，却没有说明 handler 滞后时中间意图会被最新值替换，这与“同组顺序处理”的表述存在张力，且该容量不参与固定容量监控。
- 影响：用户可能得到未预告的构造异常，或在合法配置下观察到组内中间意图静默丢失。
- 建议：在两处 KDoc 中完整说明限制与 latest-wins 语义，或直接禁止将 `CONFLATED` 用作分组容量。

### 测试

#### 10. `ScratchReviewTest` 命名临时、存在重复场景且命名风格不一致

- 来源：R1 Test 1（Medium）；R2 Name 1（Info）
- 位置：`core/src/test/java/cc/colorcat/mvi/internal/ScratchReviewTest.kt`。
- 观点：类名中的 `Scratch` 暗示临时代码；`normallyCompletingTransformerDoesNotLeaveZombieContract` 与 `ReactiveContractImplTest` 中的 transformer 提前完成测试重复，方法名还未采用项目约定的反引号格式。
- 影响：重复维护、测试归属分散，并削弱正式回归测试的可识别性。
- 建议：删除重复场景，把有独特价值的 CONCURRENT 重试隔离测试迁入 `ReactiveContractImplTest`，或将文件改为语义明确的名称并统一方法命名。

#### 11. 三个策略测试的断言不足以证明测试名所承诺的行为

- 来源：R1 Test 2（Medium）
- 位置：`internal/IntentTransformersTest.kt:120-142,359-391,424-456`。
- 观点：顺序测试只断言结果数量，并行和 fallback 分组测试只断言两个 start 标记都出现，无法区分顺序、并行或分组行为。
- 影响：实现即使退化为全串行，相关测试仍可能通过。
- 建议：增加基于索引、门控或事件先后关系的时序断言；已被其他强测试覆盖的弱测试可删除。

#### 12. Logger 工厂测试复制实现逻辑，没有测试真实工厂

- 来源：R1 Test 3（Medium）
- 位置：`LoggerTest.kt:105-139`。
- 观点：阈值测试在测试代码中手写 `if (priority >= threshold)`，没有调用真正的 `Logger(threshold)`；默认阈值测试也只断言对象非空。
- 影响：真实工厂过滤逻辑发生错误时，测试可能仍通过。
- 建议：配置 JVM Android stub 后直接调用真实 Logger 工厂验证，或将仅验证可创建对象的测试准确重命名。

#### 13. `ReactiveContractImplTest.tearDown` 是 no-op

- 来源：R1 Test 4（Low）
- 位置：`internal/ReactiveContractImplTest.kt:91-94`。
- 观点：`KMvi.configure { this }` 返回当前配置自身，并没有恢复默认配置。
- 影响：清理代码给出错误的隔离信号，实际隔离依赖下一测试的 `setUp`。
- 建议：删除该 teardown，或显式恢复 `KMvi.Configuration()`。

#### 14. 修改 KMvi 全局配置的测试没有统一隔离策略

- 来源：R1 Test 5（Low）
- 位置：`IntentHandlersTest.kt`、`IntentTransformersTest.kt`、`ReactiveContractImplTest.kt` 的相关 setUp 和临时 logger 配置。
- 观点：部分测试使用 `copy(...)` 继承旧配置，部分测试重建全新 `Configuration`；测试体内临时修改 logger 后也有未恢复的情况。
- 影响：目前可能依赖串行执行和后续 setUp，未来新增测试时容易产生顺序相关的偶发失败。
- 建议：统一在 `@Before` 重建默认 Configuration，并对测试体内的临时修改做对称恢复。

#### 15. 多处等待 `stateFlow.first` 的测试缺少超时

- 来源：R1 Test 6（Low）
- 位置：`internal/ReactiveContractImplTest.kt:148,175,696,724,751` 等。
- 观点：当预期状态永远不出现时，测试会一直挂起到 Gradle 全局超时。
- 影响：状态回归会带来数分钟级反馈延迟。
- 建议：统一用 `withTimeout` 包裹等待。

#### 16. `debounceLeading` 测试依赖真实时钟，存在偶发失败风险

- 来源：R1 Test 7（Low）
- 位置：`MviExtensionsTest.kt:78-86`。
- 观点：测试用真实 `System.nanoTime` 判断同步发射位于 50ms 窗口内，而同文件已有注入时钟的稳定测试模式。
- 影响：长 GC 或调度抢占可能让测试偶发失败。
- 建议：统一改为可注入的确定性时间源。

#### 17. `ExampleUnitTest` 是无产品价值的模板测试

- 来源：R1 Test 8（Low）；R2 Test 3（Low）
- 位置：`core/src/test/java/cc/colorcat/mvi/ExampleUnitTest.kt`。
- 观点：`addition_isCorrect` 只验证 `2 + 2 == 4`，与项目行为无关，且不符合项目测试日志约定。
- 影响：增加噪声并制造无实际意义的覆盖印象。
- 建议：删除该文件。

#### 18. 异常测试使用 `@Test(expected=...)`，与项目风格不一致

- 来源：R1 Test 9（Low）
- 位置：`MviExtensionsTest.kt:155-163`。
- 观点：该写法不能细化断言异常消息，也可能把测试体中其他同类型异常误判为通过。
- 建议：改用项目其他测试采用的 `assertThrows`。

#### 19. retry 重放测试没有覆盖事件重复投递

- 来源：R1 Test 10（Info）
- 位置：`internal/ReactiveContractImplTest.kt:1107-1136`。
- 观点：KDoc 明确警告 retry 会重放事件，但现有测试只断言状态 PartialChange 被重复应用，没有断言 event 被重复发出。
- 影响：未来事件去重或重放行为变化时，测试无法固定公开文档中的关键语义。
- 建议：让 handler 先发 event 再失败，并断言 `eventFlow` 收到两次事件。

#### 20. 生命周期收集 API 没有对应测试覆盖

- 来源：R2 Test 1（Medium）
- 位置：`MviCollects.kt` 全文件。
- 观点：`collectState`、`collectEvent`、各 Collector、`launchWithLifecycle` 和 `dispatchWithLifecycle` 等公开 API 在 `core/src/test` 中没有测试。
- 影响：生命周期启动/停止/重启、Supervisor 隔离、类型过滤和状态去重等承诺缺少回归保护。
- 建议：使用 `LifecycleRegistry` 或 lifecycle testing 工具覆盖核心生命周期行为与收集 DSL。

#### 21. View 到 Flow 的四个桥接扩展没有测试覆盖

- 来源：R2 Test 2（Medium）
- 位置：`MviExtensions.kt` 中的 `doOnClick`、`doOnLongClick`、`doOnCheckedChange`、`doOnAfterTextChanged`。
- 观点：当前 `MviExtensionsTest` 只覆盖 `asSingleFlow` 和 `debounceLeading`。
- 影响：监听器注册/移除、long-click 消费语义及文本 debounce 接线缺少回归保护。
- 建议：使用 Robolectric 或 instrumented test 覆盖取消后的监听器清理、long-click 返回值和文本 debounce。

### 文档

#### 22. handler 主动抛出的 `CancellationException` 会终止 contract，但公开文档未说明

- 来源：R1 Doc 1（Medium）；R2 Doc 2（Info）
- 位置：`internal/ReactiveContractImpl.kt:250-256`、`IntentTransformers.kt:167-171` 及相关公开 API KDoc。
- 观点：scope 仍 active 时，handler 内部的 `CancellationException` 不会被 retry，并会进入 `FatalErrorHandler`；`RetryPolicy`、`IntentHandler`、`IntentTransformer` 和 `FatalErrorHandler` 文档没有清楚说明该行为。
- 影响：用户用 `withTimeout` 或主动取消表示单个意图失败时，可能意外终止整个 contract，并取消并发兄弟意图。
- 建议：明确记录该异常的 fatal 语义并给出 `withTimeoutOrNull` 等替代方式，或重新考虑实现行为。

#### 23. `collectEvent` 文档没有警告非活跃生命周期期间的事件会永久丢失

- 来源：R1 Doc 2（Medium）
- 位置：`MviCollects.kt:254-332`，对比 `MviCollects.kt:493-495`。
- 观点：`eventFlow` 无 replay，生命周期低于目标 state 时未收集的事件会丢失；`dispatchWithLifecycle` 已有类似警告，但 `collectEvent` 和 `EventCollector` 没有。
- 影响：用户可能误以为生命周期感知收集会保留后台产生的 Toast、导航等事件。
- 建议：在对应 KDoc 中加入事件丢失警告并链接 `Contract.eventFlow` 语义。

#### 24. “一个 collector 失败不影响其他 collector”的描述遗漏了全局异常传播

- 来源：R1 Doc 3（Medium）
- 位置：`MviCollects.kt:33,547-551`。
- 观点：`SupervisorJob` 只隔离兄弟协程；用户 block 抛出的异常仍会传播到 `lifecycleScope` 的全局 `CoroutineExceptionHandler`，默认可能导致应用崩溃。
- 影响：现有文档可能被理解为 collector block 自带容错。
- 建议：说明异常仍会传播、调用方需要自行捕获；或为 collector 增加统一错误路由。

#### 25. fallback handler 被描述为 silent，但实现会记录 INFO

- 来源：R1 Doc 4（Low）
- 位置：`IntentHandlers.kt:198,219,243-248`。
- 观点：KDoc 使用 “silent” 描述 fallback dispatch，而实现会为命中 default handler 的意图记录 INFO 日志。
- 影响：依赖文档判断日志量的用户会看到意料之外的日志。
- 建议：改为“不会记录 warning，但会记录 INFO”等与实现一致的措辞。

#### 26. `doOnAfterTextChanged` 对“编辑完成”的描述不适用于零 debounce

- 来源：R1 Doc 6（Low）
- 位置：`MviExtensions.kt:256-260,278,298-302`。
- 观点：KDoc 声称在用户完成编辑后发射，但 `debounceMillis = 0` 时每次文本变化都会立即发射。
- 建议：注明只有 `debounceMillis > 0` 时才等待输入停顿。

#### 27. 两处 KDoc 示例存在安全或可编译性问题

- 来源：R1 Doc 7（Info）
- 位置：`IntentTransformers.kt:40`、`IntentHandlers.kt:288`。
- 观点：自定义 transformer 示例打印完整 intent，与库内避免记录敏感意图数据的做法不一致；handler scope 示例中的 `count` 没有定义来源，不能直接编译。
- 建议：只记录 intent 类型或显式提醒避免打印数据，并将示例改为有明确来源的表达式（如 `intent.count`）。

#### 28. AGENTS.md 对意图分类逻辑所在文件的说明不准确

- 来源：R1 Doc 8（Info）
- 位置：`AGENTS.md` 的 Key Files 小节。
- 观点：文档称 `isConcurrent`/`isSequential` 位于 `internal/InternalExtensions.kt`，实际对应的是 `IntentTransformers.kt` 中 `assignGroupTag` 的局部变量和分类逻辑，并非扩展函数。
- 建议：把意图分类逻辑归类到 `IntentTransformers.kt`。

#### 29. 生命周期收集与 View→Flow 的多个边界行为未写入 KDoc

- 来源：R1 Doc 9（Info）
- 位置：`MviCollects.kt:76-80,138-139`、`MviExtensions.kt:179-184`。
- 观点：文档未说明三类行为：每次调用收集 API 都会创建独立循环，Fragment 应使用 `viewLifecycleOwner`；生命周期重启会重建 `distinctUntilChanged` 状态并重新下发当前值；`doOnClick` 的 `trySend` 在默认缓冲区满时会静默丢事件。
- 影响：可能出现重复执行、视图重建后的旧收集未取消、带副作用的状态 block 重复触发或点击丢失。
- 建议：分别在对应 KDoc 中补充这些调用和缓冲边界。

#### 30. AGENTS.md 对 snapshots 共享流启动方式的描述过时

- 来源：R2 Doc 1（Low）
- 位置：`AGENTS.md` 的 pipeline 图，以及 `internal/ReactiveContractImpl.kt:225-246`。
- 观点：pipeline 图写成 `shareIn (Eagerly)`，来源文档认为实现实际为 snapshots 使用 `SharingStarted.Lazily`，再由 eager `stateFlow` 常驻订阅。
- 影响：会让维护者误解用于避免首个快照丢失的启动顺序设计。
- 建议：改为 `shareIn (Lazily，stateFlow 常驻订阅) → stateFlow / eventFlow`。

### 日志实现

#### 31. 默认 Logger 的单次长日志可能被 Android Log 截断

- 来源：R1 Style 1（Info）；R2 Bug 2（Info）
- 位置：`Logger.kt:89-94`。
- 观点：实现把消息和完整 stack trace 拼接后一次性传给三参数 `Log.println`，超长日志可能被 Android 平台截断。
- 影响：深层异常或嵌套 cause 的关键根因帧可能丢失。
- 建议：使用能传递 Throwable 的日志 API，或按行/按块输出堆栈。

## 正向观点

### 1. 错误与边界语义的文档和测试整体系统化

- 来源：R1 Positive 1、Positive 3；R2 Positive 1
- 观点：retry 重放、事件 best-effort、HYBRID 瓶颈、队列丢弃策略、transformer 提前完成转 fatal、dispatch 关闭语义等关键行为有较完整的文档和测试；配置对象也包含较多构造期校验。

### 2. `groupHandle` 的常规并发与资源管理设计较完整

- 来源：R1 Positive 2；R2 Positive 2
- 观点：单协程路由、每组通道、原子深度监控避免了明显数据竞争；常规完成和取消路径会关闭组通道并传播 cause，已有取消不记录失败和 stale group 重开等测试。该正向观点不排除前文单列的窄竞态问题。

### 3. 日志脱敏措施较完善

- 来源：R1 Positive 2；R2 Positive 3
- 观点：`diagnosticName` 和 `tagLabel` 主要记录类型、类名或 hash，不直接输出 tag/intent 数据；冲突警告按类型去重，`KMvi.Configuration.toString()` 也隐藏 logger、retryPolicy 和 errorHandler。

### 4. snapshots 与 stateFlow 的启动顺序规避了首快照丢失竞态

- 来源：R2 Positive 4
- 观点：来源文档认为 snapshots 的 lazy 共享配合 stateFlow 的 eager 常驻订阅，保证 replay=0 的共享流在首个订阅者就位后才产出，并有多线程 scope 测试固定该行为。

### 5. 测试整体质量较高

- 来源：R1 Positive 4
- 观点：并发与时序测试多使用 `CompletableDeferred` 门控而不是裸 sleep，断言通常明确，无 `@Ignore`；Snapshot 纯度、引用保持和日志敏感信息等行为已有针对性覆盖。

### 6. Snapshot 的状态/事件帧模型文档与测试一致

- 来源：R1 Positive 5
- 观点：event 只存活一帧、`updateState` 清除 event、`updateWith` 同帧更新状态和事件等语义有明确说明，并针对常见误用给出反例与正例。

### 7. `debounceLeading` 的时间窗口语义和边界测试较完整

- 来源：R2 Positive 5
- 观点：滑动窗口行为有时间线说明，并覆盖负 nanoTime、接近 `Long.MAX_VALUE`、非法参数等边界。

