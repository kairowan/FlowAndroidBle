# FlowAndroidBle

一个基于 Kotlin Flow 的 Android BLE（蓝牙低功耗）库，提供简洁、现代化的 API。

## 功能特性

### 核心功能
- **Flow-based 扫描** - 支持过滤器、超时、批量扫描、去重扫描
- **后台扫描桥接** - 提供 `BackgroundScanner` 和 `PendingIntent` 结果解析 helper
- **客户端别名入口** - 提供 `scanBleDevices` / `observeStateChanges` / 旧式 UUID 扫描入口
- **旧式 UUID 扫描共享语义** - 相同 UUID 集合的 `scanBleDevices(vararg UUID)` 会复用同一底层扫描，降低重复起扫开销
- **共享客户端语义** - `FlowBleClient.getInstance/create` 默认复用同一个应用级 client
- **设备句柄** - 提供 `getBleDevice` / `observeConnectionStateChanges` / `establishConnection` 风格入口
- **设备快照流** - `FlowBleDevice.snapshot` / `currentSnapshot()` 提供地址、名称和连接状态的组合视图
- **系统设备集合观察** - 已绑定设备 / 当前 GATT 连接外设都可以直接作为 Flow 观察
- **客户端环境辅助** - 提供 `getState`、扫描/连接权限检查和推荐权限列表
- **Adapter 状态观测** - 提供不掺杂权限/定位状态的原始蓝牙适配器状态流
- **示例应用** - 提供可直接构建的 `sample` 模块，演示应用侧权限声明、状态读取、设备级连接、自动重连和实时扫描
- **协程连接管理** - 支持 autoConnect、连接超时、连接配置
- **特征值操作** - 读写特征值，支持优先级、超时和重试
- **通知/指示** - 观察特征值变化，可显式选择 notification 或 indication，并支持同一特征值多观察者共享订阅
- **通知/指示便捷别名** - 提供 `setupNotification` / `setupIndication`
- **描述符操作** - 读写描述符
- **值解析辅助** - 提供 `ValueInterpreter` 风格的整数 / 浮点 / 字符串解码
- **MTU 协商** - 请求特定 MTU 大小
- **连接优先级** - 请求低功耗 / 平衡 / 高吞吐连接参数
- **PHY 控制** - 读取当前 PHY 并请求 1M / 2M / Coded 首选 PHY（API 26+）
- **RSSI 读取** - 读取信号强度
- **绑定管理** - 设备配对/解绑
- **连接监督** - 连接健康监控
- **自动重连会话** - 提供 `AutoReconnectSession`，可在断线后自动建立下一条连接，并支持恢复初始化
- **服务发现状态流** - `BleConnection.servicesState` 和 `AutoReconnectSession.services` 可直接驱动 UI/状态机
- **连接快照流** - `BleConnection.snapshot` / `currentSnapshot()` 和 `AutoReconnectSession.activeConnectionSnapshot` 提供组合后的连接状态视图
- **会话状态观测** - `AutoReconnectSession` 暴露 `activeConnectionState`、`lastError` 和 `snapshot`，便于 UI/状态机直接订阅
- **会话关闭辅助** - `AutoReconnectSession.stop()` 适合挂起上下文，`cancel()` 适合 `onDestroy()` / `onCleared()` 这类非挂起收尾

### 高级功能
- **操作队列** - 优先级队列，顺序执行 GATT 操作
- **批量扫描** - 批量收集扫描结果，减少回调频率
- **去重扫描** - 自动过滤重复设备
- **多设备管理** - 同时管理多个设备连接
- **多设备连接自动清理** - `MultiDeviceManager` 会在连接断开后自动移除陈旧连接
- **多设备连接集合流** - `MultiDeviceManager.activeConnections` 可直接驱动 UI 或状态机
- **单设备单活连接保护** - 同一 MAC 地址同时只允许一个活动中或建立中的连接
- **蓝牙状态监控** - 监控蓝牙和位置服务状态
- **日志系统** - 可配置的日志记录
- **API 33+ 兼容** - 支持新旧 BLE 回调变体
- **自动资源清理** - Flow 取消时自动清理资源
- **线程安全** - 所有 GATT 操作通过 Mutex 序列化
- **精确 UUID 寻址** - 可通过 service UUID + characteristic UUID 避免重复 UUID 歧义
- **服务查询助手** - 提供 `getServices` / `getService` / `getCharacteristic` / `getDescriptor`
- **类型化查找异常** - service / characteristic / descriptor 缺失或 UUID 歧义会抛出明确 BLE 异常
- **更细粒度运行时异常** - GATT 状态错误、启动失败、回调超时、断开和扫描前置失败都会暴露更明确的异常类型
- **跨重连通知恢复** - `AutoReconnectSession.observeCharacteristicByUuid(...)` 可在新连接建立后继续观察同一特征值
- **恢复计划 DSL** - 用 `autoReconnectRecoveryPlan { ... }` 模板化 MTU、descriptor、连接参数和业务初始化恢复
- **长写支持** - 根据 MTU 自动分片写入大 payload
- **长写 Builder** - 支持高级长写配置和逐批确认钩子
- **长写重试策略** - 可对单个失败分片做自定义重试控制
- **高级自定义操作** - 可将原生 Android GATT 调用接入同一串行队列

## 项目结构

```
com.flowble/
├── FlowBleClient.kt           # 主入口点
├── BleScanner.kt              # BLE 扫描
├── FlowBleDevice.kt           # 设备级句柄
├── BleConnection.kt           # 连接接口
├── LongWriteOperationBuilder.kt # 高级长写 Builder
├── LongWriteAcknowledgement.kt # 长写分片确认信息
├── LongWriteAckStrategy.kt    # 长写确认策略
├── QueuedGattOperationScope.kt # 高级自定义 GATT 操作作用域
├── QueuedGattCallbackType.kt  # 自定义操作回调类型
├── ScannerConfig.kt           # 扫描配置
├── BatchScanner.kt            # 批量扫描
├── MultiDeviceManager.kt      # 多设备管理
├── BondingManager.kt          # 绑定管理
├── BluetoothStateMonitor.kt   # 蓝牙状态监控
├── ConnectionSupervisor.kt    # 连接监督
├── BleLogger.kt               # 日志系统
├── model/
│   ├── ConnectionState.kt     # 连接状态
│   ├── BondState.kt           # 绑定状态
│   ├── BleAdapterState.kt     # 原始蓝牙适配器状态
│   ├── BleState.kt            # BLE 系统状态
│   ├── BleScanResult.kt       # 扫描结果
│   ├── BleDeviceSnapshot.kt   # 设备句柄组合快照
│   ├── BlePhy.kt              # 当前 PHY
│   ├── ConnectionPriority.kt  # 连接优先级
│   ├── BleService.kt          # GATT 服务
│   ├── BleCharacteristic.kt   # 特征值
│   ├── BleDescriptor.kt       # 描述符
│   ├── CharacteristicProperty.kt
│   ├── CharacteristicObservationMode.kt
│   ├── PhyRequest.kt
│   ├── PhyType.kt
│   ├── PhyOption.kt
│   ├── WriteType.kt           # 写入类型
│   ├── ConnectionConfig.kt    # 连接配置
│   ├── BleConnectionSnapshot.kt # 连接组合快照
│   ├── AutoReconnectState.kt  # 自动重连会话状态
│   ├── AutoReconnectSnapshot.kt # 自动重连会话快照
│   ├── OperationPriority.kt   # 操作优先级
│   └── ScanFilterBuilder.kt   # 扫描过滤器构建器
├── exception/
│   └── BleExceptions.kt       # 异常类
├── internal/
│   ├── Constants.kt           # 常量
│   ├── GattCallbackRouter.kt  # GATT 回调路由
│   ├── GattOperationQueue.kt  # 操作队列
│   ├── CharacteristicCache.kt # 特征值缓存
│   ├── LongWriteSupport.kt    # 长写分片辅助
│   ├── PhySupport.kt          # PHY 辅助
│   └── BleConnectionImpl.kt   # 连接实现
├── helpers/
│   └── ValueInterpreter.kt    # 标准 BLE 值解析辅助
└── ext/
    ├── ContextExt.kt          # Context 扩展
    ├── ByteArrayExt.kt        # ByteArray 扩展
    └── BluetoothDeviceExt.kt  # BluetoothDevice 扩展
```

额外模块：

- `ble/` - 主库模块
- `sample/` - 最小可运行示例 App，演示共享 client、权限处理、设备级连接和扫描

## 使用方法

### 初始化客户端

```kotlin
val client = FlowBleClient.getInstance(context)
val sameClient = FlowBleClient.create(context) // create() 是 getInstance() 的别名
```

### 示例应用

仓库自带了一个可以直接编译安装的 `sample` 模块，用来演示：

- `FlowBleClient.getInstance(context)` 的共享 client 用法
- App 自己声明 BLE 权限，并决定 `BLUETOOTH_SCAN` 的 `neverForLocation` 策略
- 如何读取 `getState()` / `getAdapterState()`
- 如何观察并点选 bonded devices / connected peripherals
- 如何通过 `FlowBleDevice.establishConnectionFlow(...)` 建立并管理单设备连接
- 如何通过 `createAutoReconnectSession(...)` 启动可观察的自动重连会话
- 如何从最近扫描结果中选择设备并把地址带入连接流程
- 如何基于推荐权限列表请求运行时权限
- 如何启动和停止一个持续扫描的 Flow

构建命令：

```bash
./gradlew :sample:assembleDebug
```

### BLE 状态监控

```kotlin
val stateMonitor = client.stateMonitor()

// 监控 BLE 系统状态
stateMonitor.observeBleState().collect { state ->
    when (state) {
        BleState.Ready -> println("BLE 就绪")
        BleState.BluetoothDisabled -> println("蓝牙已关闭")
        BleState.LocationServicesDisabled -> println("位置服务已关闭")
        else -> println("状态: $state")
    }
}

// 监控蓝牙开关
stateMonitor.observeBluetoothEnabled().collect { enabled ->
    println("蓝牙: ${if (enabled) "开启" else "关闭"}")
}

// 客户端别名命名
client.observeStateChanges().collect { state ->
    println("BLE 状态: $state")
}

// 只关心原始蓝牙适配器状态时，可以直接观察更窄的 state
client.observeAdapterStateChanges().collect { state ->
    println("Adapter 状态: $state")
}

// 获取当前状态快照
val currentState = client.getState()
println("当前 BLE 状态: $currentState")
println("当前 Adapter 状态: ${client.getAdapterState()}")

// 权限辅助
val scanGranted = client.isScanRuntimePermissionGranted()
val connectGranted = client.isConnectRuntimePermissionGranted()
val scanPermissions = client.getRecommendedScanRuntimePermissions()
val connectPermissions = client.getRecommendedConnectRuntimePermissions()
```

`observeBleState()` 会先发出当前状态再继续观察变化；
`observeStateChanges()` 也会先发出当前状态，再继续发出后续变化；
`observeAdapterStateChanges()` 则只反映蓝牙适配器本身的 on/off/turning 状态。

### 扫描设备

```kotlin
// 基础扫描
client.scan().collect { result ->
    println("设备: ${result.address} (${result.deviceName})")
}

// 客户端命名
client.scanBleDevices().collect { result ->
    println("设备: ${result.address} (${result.deviceName})")
    println("可连接: ${result.isConnectable}")
    println("广播 Flags: ${result.advertiseFlags}")
    println("广播 TxPower: ${result.txPowerLevel}")
}

// 要求所有 UUID 都出现在广播里
client.scanBleDevices(
    UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
).collect { result ->
    println("广播服务: ${result.advertisedServiceUuids}")
}

// 相同 UUID 集合会共享同一底层扫描
val sharedHeartRateScan = client.scanBleDevices(
    UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
)

// 自定义 ScanSettings + ScanFilter，默认不会自动超时
client.scanBleDevices(
    ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build(),
    scanFilter {
        deviceName("MyDevice")
    }
).collect { result ->
    println("设备: ${result.address}")
}

// 带配置的扫描
client.scan {
    setTimeoutMs(10000)
    addFilter(scanFilter {
        deviceName("MyDevice")
        serviceUuid(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))
    })
}.collect { result ->
    // 处理扫描结果
}

// 扫描特定设备
client.scanner().scanForDevice("AA:BB:CC:DD:EE:FF").collect { result ->
    println("找到目标设备!")
}

client.scanBleDevice("AA:BB:CC:DD:EE:FF").collect { result ->
    println("找到目标设备!")

    // 从扫描结果直接拿设备句柄
    val deviceHandle = result.getBleDevice(client)
    println("设备句柄地址: ${deviceHandle.address}")
}

// 直接取 service data / manufacturer data，不必自己回到原始 ScanRecord
client.scanBleDevices().collect { result ->
    val batteryServiceData = result.getServiceData(
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    )
    val manufacturerData = result.getManufacturerSpecificData(0x004C)
}

// 如果你自己接了平台的 PendingIntent 后台扫描，也可以把 Intent 解析回 FlowAndroidBle 模型
val backgroundResults = BleScanner.getBackgroundScanResults(intent)
val backgroundCallbackType = BleScanner.getBackgroundScanCallbackType(intent)
val backgroundError = BleScanner.getBackgroundScanError(intent)

// 也可以直接通过库提供的 BackgroundScanner 发起/停止 PendingIntent 扫描
client.backgroundScanner().startScan(callbackIntent)
client.backgroundScanner().stopScan(callbackIntent)
client.getBackgroundScanner().startScan(callbackIntent)

// 批量扫描 - 减少回调频率
client.batchScanner(batchInterval = 1000L).scanBatch().collect { batch ->
    println("收到 ${batch.size} 个设备")
}

// 去重扫描 - 每个设备只出现一次
client.batchScanner().scanDistinct().collect { result ->
    println("新设备: ${result.address}")
}
```

### 设备句柄

```kotlin
val device = client.getBleDevice("AA:BB:CC:DD:EE:FF")
println("MAC: ${device.macAddress}")

device.observeConnectionStateChanges().collect { state ->
    println("状态变化: $state")
}

device.snapshot.collect { snapshot ->
    println("设备快照: $snapshot")
}

// 如果你手上已经是 Android 原生 BluetoothDevice，也可以直接拿稳定句柄
val sameHandle = bluetoothDevice.getBleDevice(client)

val connection = device.establishConnection(autoConnect = false)
println("当前状态: ${device.getConnectionState()}")
println("设备名: ${device.getName()}")
println("当前设备快照: ${device.currentSnapshot()}")

// operation timeout 重载
val backgroundConnection = device.establishConnection(
    autoConnect = true,
    operationTimeoutMs = 45_000L
)

// 长生命周期连接，
// 开始收集时建连，断开或取消收集时自动结束并关闭连接
device.establishConnectionFlow(autoConnect = false).collect { activeConnection ->
    println("Flow 方式拿到连接: ${activeConnection.connectionState.value}")
}
```

`observeConnectionStateChanges()` 发出当前连接状态，再继续发出后续变化；
如果你只需要同步读取当前值，也可以直接用 `connectionState.value` 或 `getConnectionState()`。

`snapshot` / `currentSnapshot()` 则更适合 UI：它会把稳定地址、最佳努力拿到的设备名、
以及当前连接状态组合成一份轻量模型。

`FlowBleClient.connect(...)` 和 `device.establishConnection(...)` 走的是同一套连接租约管理；
同一 MAC 地址如果已经处于连接中或已连接状态，再发起第二次连接会直接失败。
另外，布尔重载里的 `autoConnect = true` ：
默认不会再额外套一个 30 秒初始连接超时；如果你想自定义超时，请直接传
`operationTimeoutMs` 或完整的 `ConnectionConfig`。

### 连接设备

```kotlin
// 快速连接
val connection = client.connect("AA:BB:CC:DD:EE:FF")

// 带配置的连接
val connection = client.connect("AA:BB:CC:DD:EE:FF", ConnectionConfig.build {
    setAutoConnect(false)
    setConnectionTimeout(10000)
    setRetryCount(2)
    setPreferredPhy(PhyType.LE_2M)
})

// 使用预设配置
val connection = client.connect("AA:BB:CC:DD:EE:FF", ConnectionConfig.QUICK)
val connection = client.connect("AA:BB:CC:DD:EE:FF", ConnectionConfig.AUTO_CONNECT)

// 监控连接状态
connection.connectionState.collect { state ->
    when (state) {
        ConnectionState.Connected -> println("已连接!")
        ConnectionState.Disconnected -> println("已断开!")
        else -> {}
    }
}

// 组合后的连接快照
connection.snapshot.collect { snapshot ->
    println(
        "state=${snapshot.connectionState}, " +
            "mtu=${snapshot.mtu}, " +
            "services=${snapshot.services?.size ?: 0}"
    )
}

// 当前系统层面已绑定/已连接的设备句柄
val bonded = client.getBondedDevices()
val connected = client.getConnectedPeripherals()

client.observeBondedDevices().collect { devices ->
    println("已绑定设备: ${devices.map { it.address }}")
}

client.observeConnectedPeripherals().collect { devices ->
    println("当前 GATT 连接设备: ${devices.map { it.address }}")
}
```

`setPreferredPhy(...)` 会在连接建立后立即请求 PHY，要求 Android 8.0（API 26）及以上。

### 发现服务

```kotlin
val services = connection.discoverServices()
services.forEach { service ->
    println("服务: ${service.uuid}")
    service.characteristics.forEach { char ->
        println("  特征: ${char.uuid}")
        println("  属性: ${char.properties}")
    }
}

// 或者使用更直接的查询助手
val currentServices = connection.getServices()
val heartRateService = connection.getService(myServiceUuid)
val measurement = connection.getCharacteristic(myServiceUuid, myCharacteristicUuid)
val cccd = connection.getDescriptor(
    myServiceUuid,
    myCharacteristicUuid,
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
)

// 拿到服务树之后，也可以继续在对象上查找
val measurementFromService = heartRateService.getCharacteristic(myCharacteristicUuid)
val cccdFromService = heartRateService.getDescriptor(
    myCharacteristicUuid,
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
)
val cccdFromCharacteristic = measurementFromService.getDescriptor(
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
)

// 如果 UI 更适合订阅状态，也可以直接观察最新服务树
connection.servicesState.collect { services ->
    println("当前服务数: ${services?.size ?: 0}")
}
```

### 读写特征值

```kotlin
// 通过特征值对象读写
val characteristic = connection.getCharacteristic(myServiceUuid, myCharacteristicUuid)

// 读取
val value = connection.readCharacteristic(characteristic)
println("读取: ${value.toHexString()}")

// 写入
val written = connection.writeCharacteristic(characteristic, byteArrayOf(0x01, 0x02))
println("写回: ${written.toHexString()}")

// 带优先级和超时的写入
connection.writeCharacteristic(
    characteristic,
    byteArrayOf(0x01, 0x02),
    config = OperationConfig.highPriority()
)

// 通过 UUID 读写（自动发现服务）
val value = connection.readCharacteristicByUuid(myCharUuid)
val writtenByUuid = connection.writeCharacteristicByUuid(myCharUuid, byteArrayOf(0x01))
println("UUID 写回: ${writtenByUuid.toHexString()}")

// descriptor 也支持 service/characteristic/descriptor 三段 UUID 的直接入口
val cccdValue = connection.readDescriptor(
    myServiceUuid,
    myCharUuid,
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
)
connection.writeDescriptor(
    myServiceUuid,
    myCharUuid,
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
    byteArrayOf(0x01, 0x00)
)

// 长写：默认按当前 MTU 自动分片
connection.writeCharacteristicLong(characteristic, largePayload)

// 长写：手动指定 chunk 大小和 chunk 间隔
connection.writeCharacteristicLongByUuid(
    myCharUuid,
    largePayload,
    writeType = WriteType.NO_RESPONSE,
    maxChunkSize = 128,
    interChunkDelayMs = 15L
)

// 调用方式
val echoed = connection.createNewLongWriteBuilder()
    .setCharacteristic(characteristic)
    .setBytes(largePayload)
    .setMaxBatchSize(128)
    .setInterChunkDelayMs(15L)
    .setWriteOperationAckStrategy { ack ->
        println("已写入 ${ack.bytesWritten}/${ack.totalBytes}")
    }
    .setWriteOperationRetryStrategy { failure ->
        if (failure.attempt < 3) {
            LongWriteRetryDecision.RetryAfter(delayMs = 50)
        } else {
            LongWriteRetryDecision.Abort
        }
    }
    .build()
```

### 观察通知

```kotlin
import com.flowble.model.CharacteristicObservationMode

// 通过特征值对象观察
connection.observeCharacteristic(characteristic).collect { value ->
    println("通知: ${value.toHexString()}")
}

// 明确要求 indication
connection.observeCharacteristic(
    characteristic,
    CharacteristicObservationMode.INDICATION
).collect { value ->
    println("指示: ${value.toHexString()}")
}

// 通过 UUID 观察（自动发现服务）
connection.observeCharacteristicByUuid(myCharUuid).collect { value ->
    println("通知: ${value.toHexString()}")
}

// UUID 入口
connection.setupNotification(myCharUuid).collect { value ->
    println("通知: ${value.toHexString()}")
}

connection.setupNotification(myServiceUuid, myCharUuid).collect { value ->
    println("service-scoped 通知: ${value.toHexString()}")
}

// 命名风格
connection.setupNotification(characteristic).collect { value ->
    println("通知: ${value.toHexString()}")
}

// 原本的 setupNotification(..., setupMode) 结构，
// 现在也可以直接用同名重载
connection.setupNotification(
    characteristic,
    NotificationSetupMode.QUICK_SETUP
).collect { notifications ->
    notifications.collect { value ->
        println("快速建立后的通知: ${value.toHexString()}")
    }
}

// 外层表示 setup 生命周期，内层才是数据流
connection.setupNotificationSession(
    characteristic,
    setupMode = NotificationSetupMode.QUICK_SETUP
).collect { notifications ->
    notifications.collect { value ->
        println("快速建立后的通知: ${value.toHexString()}")
    }
}

connection.setupIndicationByUuid(myServiceUuid, myCharUuid).collect { value ->
    println("指示: ${value.toHexString()}")
}

// 同一特征值、同一模式下允许多个 collector 共享同一底层通知配置

// setupMode 语义：
// DEFAULT: 等待 CCCD 写成功后再暴露流
// QUICK_SETUP: 先暴露流，再异步写 CCCD；后续写失败会终止该 observation
// COMPAT: 只做本地 setCharacteristicNotification，适合无 CCCD 的外设
```

### MTU 协商

```kotlin
val mtu = connection.requestMtu(512)
println("协商 MTU: $mtu")

connection.requestConnectionPriority(ConnectionPriority.HIGH)
```

### PHY 读取与切换

```kotlin
import com.flowble.model.PhyOption
import com.flowble.model.PhyRequest
import com.flowble.model.PhyType

val currentPhy = connection.readPhy()
println("当前 PHY: tx=${currentPhy.txPhy}, rx=${currentPhy.rxPhy}")

val updatedPhy = connection.requestPhy(
    PhyRequest(
        txPhys = setOf(PhyType.LE_2M),
        rxPhys = setOf(PhyType.LE_2M),
        option = PhyOption.NO_PREFERRED
    )
)
println("更新后 PHY: tx=${updatedPhy.txPhy}, rx=${updatedPhy.rxPhy}")

connection.phy.collect { phy ->
    println("PHY 状态: $phy")
}
```

### 高级自定义队列操作

```kotlin
import com.flowble.QueuedGattCallbackType
import kotlinx.coroutines.delay

// 对于没有专门封装的原生 Android BLE API，可以接入同一串行 GATT 队列
connection.queue {
    val started = gatt.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    check(started) { "请求连接优先级失败" }
    delay(500)
}

// 需要等待回调的操作可显式声明回调类型
val phy = connection.queue {
    execute(
        callbackType = QueuedGattCallbackType.PHY_READ
    ) {
        gatt.readPhy()
        true
    }
}
println("自定义读取 PHY: $phy")
```

### RSSI 读取

```kotlin
val rssi = connection.readRssi()
println("信号强度: ${rssi} dBm")
```

### 绑定管理

```kotlin
val bondingManager = client.bondingManager()

// 创建绑定
val bondState = bondingManager.createBond(device)
println("绑定状态: $bondState")

// 移除绑定
bondingManager.removeBond(device)

// 监控绑定状态
bondingManager.observeBondState(device).collect { state ->
    println("绑定状态: $state")
}
```

### 连接监督

```kotlin
val supervisor = ConnectionSupervisor(
    connectionState = connection.connectionState,
    onConnectionLost = {
        println("连接丢失或长时间静默!")
        // 可在这里尝试重连
    },
    checkInterval = 5000L,
    maxSilentDuration = 30000L
)
supervisor.start()

// 当收到数据时通知监督器
connection.observeCharacteristic(char).collect { value ->
    supervisor.notifyDataReceived()
    // 处理数据
}
```

### 自动重连

```kotlin
val device = client.getBleDevice("AA:BB:CC:DD:EE:FF")
val session = device.createAutoReconnectSession(
    config = ConnectionConfig.build {
        setAutoConnect(false)
        setConnectionTimeout(10000)
        setRetryDelay(1500)
    },
    reconnectDelayMs = 1500L,
    maxReconnectAttempts = Int.MAX_VALUE,
    discoverServicesOnConnect = true,
    recoveryPlan = autoReconnectRecoveryPlan {
        requestMtu(247)
        requestConnectionPriority(ConnectionPriority.HIGH)
        writeDescriptorByUuid(
            serviceUuid = myServiceUuid,
            characteristicUuid = myCharUuid,
            descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            value = byteArrayOf(0x01, 0x00)
        )
        readCharacteristicByUuid(myServiceUuid, myCharUuid) { value ->
            println("恢复时读到的值: ${value.toHexString()}")
        }
        readRssi { value ->
            println("恢复时 RSSI: $value")
        }
        run {
            // 每次拿到新连接后都要恢复的业务初始化
            readCharacteristicByUuid(myServiceUuid, myVersionCharUuid)
        }
    },
    onConnectionReady = {
        // 还可以补充少量完全自定义的恢复逻辑
        println("连接恢复完成，当前 MTU=${mtu.value}")
    },
    scope = viewModelScope
)

session.start()

session.state.collect { state ->
    println("重连状态: $state")
}

session.connection.collect { activeConnection ->
    if (activeConnection != null) {
        println("拿到已经恢复完成的新连接实例")
    }
}

session.activeConnectionSnapshot.collect { snapshot ->
    println("当前活动连接快照: $snapshot")
}

session.services.collect { services ->
    println("当前活动连接服务数: ${services?.size ?: 0}")
}

session.activeConnectionState.collect { state ->
    println("当前活动连接状态: $state")
}

session.lastError.collect { error ->
    if (error != null) {
        println("最近一次连接/恢复失败: ${error.message}")
    }
}

session.snapshot.collect { snapshot ->
    println(
        "会话快照: state=${snapshot.state}, " +
            "active=${snapshot.hasActiveConnection}, " +
            "connectionState=${snapshot.activeConnectionState}, " +
            "mtu=${snapshot.activeConnectionSnapshot?.mtu}, " +
            "lastError=${snapshot.lastError?.message}"
    )
}

// 或者直接在 session 上等待可用连接
val activeConnection = session.awaitConnection()
println("当前会话连接状态: ${activeConnection.connectionState.value}")

// 常见操作也可以直接走 session，会自动落到当前活动连接
val mtu = session.requestMtu(247)
val payload = session.readCharacteristicByUuid(myServiceUuid, myCharUuid)
val echoed = session.writeCharacteristicByUuid(myServiceUuid, myCharUuid, byteArrayOf(0x01))
val phy = session.requestPhy(PhyType.LE_2M, PhyType.LE_CODED, PhyOption.S2)

val longWriteEchoed = session.createNewLongWriteBuilder()
    .setCharacteristicUuid(myServiceUuid, myCharUuid)
    .setBytes(largePayload)
    .setMaxBatchSize(128)
    .build()

// 高级操作也可以直接绑定到当前活动连接
session.withConnection {
    requestConnectionPriority(ConnectionPriority.HIGH)
}

session.queue {
    execute {
        gatt.readPhy()
        true
    }
}

// 跨重连持续观察：底层断开重连后，会自动在新连接上重新订阅
session.setupNotificationByUuid(myServiceUuid, myCharUuid).collect { value ->
    println("连续通知: ${value.toHexString()}")
}

// 如果你已经拿到了模型对象，也可以直接把 BleCharacteristic 交给 session
val characteristic = session.getCharacteristic(myServiceUuid, myCharUuid)
    ?: error("Characteristic not found")

session.observeCharacteristic(characteristic).collect { value ->
    println("对象入口连续观察: ${value.toHexString()}")
}

session.setupNotification(characteristic).collect { value ->
    println("对象入口连续通知: ${value.toHexString()}")
}

// UUID 入口
session.setupNotification(myCharUuid).collect { value ->
    println("连续通知: ${value.toHexString()}")
}

session.setupNotification(
    myCharUuid,
    NotificationSetupMode.COMPAT
).collect { value ->
    println("兼容模式连续通知: ${value.toHexString()}")
}

// 也可以把 QUICK_SETUP / COMPAT 等 setupMode 带进跨重连场景
session.setupNotificationByUuid(
    myServiceUuid,
    myCharUuid,
    setupMode = NotificationSetupMode.COMPAT
).collect { value ->
    println("兼容模式通知: ${value.toHexString()}")
}

// “外层 setup / 内层数据流” 结构，
// session 级别也支持跨重连的 session API
session.setupNotificationSessionByUuid(
    myServiceUuid,
    myCharUuid,
    setupMode = NotificationSetupMode.COMPAT
).collectLatest { updates ->
    updates.collect { value ->
        println("session 风格通知: ${value.toHexString()}")
    }
}

session.setupNotificationSession(
    characteristic,
    setupMode = NotificationSetupMode.COMPAT
).collectLatest { updates ->
    updates.collect { value ->
        println("对象入口 session 风格通知: ${value.toHexString()}")
    }
}

// 如果你自己处理了其他业务数据，也可以手动喂给 session 的静默监督器
session.notifyDataReceived()

// 非挂起生命周期回调里也可以同步结束 session
override fun onCleared() {
    session.cancel()
}

// 结束时停止重连；默认也会断开当前连接
session.stop()
```

`ConnectionConfig.autoConnect` 只是 Android 原生 `connectGatt(..., autoConnect = true)` 的后台连接提示，
并不等于“断线后自动重连”。真正的自动恢复连接请使用 `AutoReconnectSession`。

`discoverServicesOnConnect = true` 会在每次新连接建立后先做一次 `discoverServices()`；
`recoveryPlan` 适合模板化恢复 MTU、descriptor、连接参数和业务初始化；
`onConnectionReady` 则适合补充少量完全自定义的恢复逻辑。

除了 `state` 和 `connection`，`activeConnectionState` 会直接镜像当前活动连接自己的
`connectionState`；`lastError` 会保留最近一次连接或恢复失败；`snapshot` 则把这些信号
连同 `activeConnectionSnapshot` 一起折叠成一个 `StateFlow<AutoReconnectSnapshot>`，
更适合直接绑定 UI 或会话状态机。

`activeConnectionSnapshot` 会把当前活动连接的 `connectionState`、`mtu`、`phy` 和
`services` 组合成一条更适合 UI 直接订阅的状态流。

`services` 会镜像当前活动连接的 `servicesState`：服务发现前是 `null`，
成功 `discoverServices()` 后会变成最新服务树，连接断开后会重新回到 `null`。

`AutoReconnectSession` 还提供了 `awaitConnection()`、`requestMtu()`、
`getServices()`、`readCharacteristic()` / `readCharacteristicByUuid()`、
`writeCharacteristic()` / `writeCharacteristicByUuid()`、`readDescriptor()` /
`writeDescriptor()`、`requestPhy()`、`createNewLongWriteBuilder()`、`readRssi()` 等会话级 helper，
适合把“始终操作当前活动连接”的逻辑收拢到同一个入口。

如果恢复阶段需要“读完再同步到业务状态”，`autoReconnectRecoveryPlan { ... }`
还支持 `readCharacteristic { ... }` / `readCharacteristicByUuid { ... }`、
`readDescriptor { ... }` / `readDescriptorByUuid { ... }`、`writeCharacteristic(...)`、
`writeDescriptor(...)`、`readRssi { ... }`、`readPhy { ... }` 这种恢复步骤。

如果你之前依赖 `NotificationSetupMode.QUICK_SETUP` 或 `COMPAT`，可以直接用
`AutoReconnectSession.setupNotificationByUuid(...)` / `setupIndicationByUuid(...)`
把同样的 setup 语义带到跨重连订阅里；如果你更偏好这种外层 setup、
内层数据流的结构，也可以直接用
`setupNotificationSessionByUuid(...)` / `setupIndicationSessionByUuid(...)`。

### 多设备管理

```kotlin
val multiDevice = client.multiDeviceManager()

// 连接多个设备
val conn1 = multiDevice.connect("AA:BB:CC:DD:EE:01")
val conn2 = multiDevice.connect("AA:BB:CC:DD:EE:02")

// 检查连接状态
println("设备1: ${multiDevice.isConnected("AA:BB:CC:DD:EE:01")}")
println("连接数: ${multiDevice.connectionCount()}")

// 获取连接
val conn = multiDevice.getConnection("AA:BB:CC:DD:EE:01")

// 断开特定设备
multiDevice.disconnect("AA:BB:CC:DD:EE:01")

// 断开所有设备
multiDevice.disconnectAll()
```

### 日志记录

```kotlin
// 全局日志级别入口
FlowBleClient.setLogLevel(BleLogger.Level.DEBUG)

//  logger 入口，参数顺序也是 level / tag / message
FlowBleClient.setLogger { level, tag, message ->
    println("[$level] ${tag ?: "FlowBLE"}: $message")
}

// 启用日志
BleLogger.setEnabled(true)
BleLogger.setLevel(BleLogger.Level.DEBUG)

// 自定义日志器
BleLogger.setCustomLogger { level, message, tag ->
    println("[$level] ${tag ?: "FlowBLE"}: $message")
}

FlowBleClient.clearLogger()
```

### 值解析辅助

```kotlin
import com.flowble.helpers.ValueInterpreter

val heartRate = ValueInterpreter.getIntValue(
    payload,
    ValueInterpreter.FORMAT_UINT8,
    offset = 1
)

val temperature = ValueInterpreter.getFloatValue(
    payload,
    ValueInterpreter.FORMAT_SFLOAT,
    offset = 0
)

val deviceLabel = ValueInterpreter.getStringValue(payload, offset = 0)
```

## 错误处理

库抛出特定异常以区分不同错误场景：

```kotlin
try {
    val connection = client.connect(address)
} catch (e: BleException) {
    when (e) {
        is GattException -> println("GATT 错误: ${e.operationType} / ${e.status}")
        is BleGattCannotStartException -> println("GATT 启动失败: ${e.operationType}")
        is BleGattCallbackTimeoutException -> println("GATT 超时: ${e.message}")
        is BleDisconnectedException -> println("连接断开: ${e.deviceAddress} / ${e.status}")
        is ConnectionException -> println("连接错误: ${e.message}")
        is BleScanException -> println("扫描错误: ${e.reason}")
        is ScanFailedException -> println("扫描失败: ${e.errorCode}")
        is TimeoutException -> println("超时: ${e.message}")
        else -> println("BLE 错误: ${e.message}")
    }
}
```

## 扫描过滤器构建器

```kotlin
val filter = scanFilter {
    deviceName("MyDevice")
    deviceAddress("AA:BB:CC:DD:EE:FF")
    serviceUuid(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))
}
```

## 配置选项

### 连接配置

```kotlin
val config = ConnectionConfig.build {
    setAutoConnect(true)           // Android 后台连接提示，不等于断线自动重连
    setConnectionTimeout(30000)    // 连接超时 30 秒
    setOperationTimeout(10000)     // 操作超时 10 秒
    setRetryCount(3)              // 重试 3 次
    setRetryDelay(1000)           // 重试延迟 1 秒
}
```

### 操作配置

```kotlin
// 高优先级操作
val written = connection.writeCharacteristic(char, data, config = OperationConfig.highPriority())

// 带超时的操作
connection.readCharacteristic(char, config = OperationConfig.withTimeout(5000))

// 带重试的操作
connection.writeCharacteristic(char, data, config = OperationConfig.withRetry(3))
```

## 权限要求

```xml
<!-- Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- 旧版 Android -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
```

**注意**：库不负责检查或请求权限，应用需要自行处理运行时权限。

库不会替应用在 manifest 里注入扫描/连接权限，也不会强制声明
`android:usesPermissionFlags="neverForLocation"`。如果你的应用明确不会把 BLE
扫描结果用于推断物理位置，可以在应用自己的 manifest 里按需声明：

```xml
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
```

如果你要做后台扫描，权限策略需要由应用自行决定，尤其是 Android 10/11 上的位置权限
以及 Android 12+ 上的 `BLUETOOTH_SCAN` 配置。FlowAndroidBle 现在提供了
`BackgroundScanner` 以及 `BleScanner.getBackgroundScanResults(...)` 这类桥接 API，
但后台唤醒、广播分发和最终业务调度仍然由应用侧负责接住 `PendingIntent` 回调。

可以通过以下 API 辅助判断：

```kotlin
client.getState()
client.isScanRuntimePermissionGranted()
client.isConnectRuntimePermissionGranted()
client.getRecommendedScanRuntimePermissions()
client.getRecommendedConnectRuntimePermissions()
```

## 平台与能力说明

- 当前 FlowAndroidBle 的 `minSdk` 是 21。
- 当前覆盖范围从 API 21 开始，API 21 以下的扫描支持还没有补齐。
- 库当前已经提供了大量便捷入口，并补上了更细粒度的 GATT/scan/disconnect 异常；
  但后台扫描体验、部分扫描前置条件语义和更完整的异常矩阵仍在继续收敛中。

## 架构设计

```
应用代码
   │
FlowBleClient              (入口点)
   │
   ├── BleScanner           (扫描)
   ├── BleConnection        (连接)
   ├── BatchScanner         (批量扫描)
   ├── MultiDeviceManager   (多设备管理)
   ├── BondingManager       (绑定管理)
   └── BluetoothStateMonitor(状态监控)
           │
      GattCallbackRouter    (核心引擎)
           │
      Android BluetoothGatt
```

## 依赖

- Android 5.0+ (API 21)
- Kotlin 2.1.0+
- Coroutines 1.9.0+

## 许可证

MIT License
