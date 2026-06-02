# FlowAndroidBle Sample

这个 `sample` 模块是一个最小可运行的示例 App，用来演示几件最常见的接入动作：

- 通过 `FlowBleClient.getInstance(context)` 复用共享 client
- 在 App 侧声明 BLE 权限，并决定 `BLUETOOTH_SCAN` 的 `neverForLocation` 策略
- 实时观察当前 BLE / adapter 状态
- 实时观察 bonded devices 集合，并可点选设备
- 实时观察系统当前 connected peripherals 集合，并可点选设备
- 用 `FlowBleDevice.snapshot` / `currentSnapshot()` 驱动设备状态展示
- 输入设备 MAC 地址并走一条 `FlowBleDevice.establishConnectionFlow(...)` 连接链路
- 连接后做一次服务发现，并展示 MTU / services / characteristics 摘要
- 用同一个地址启动 `AutoReconnectSession`，观察会话状态、活动连接状态和最近错误
- 用 `AutoReconnectSession.snapshot` 直接观察当前活动连接的恢复后状态
- 通过最近扫描结果列表快速把设备地址带入连接面板
- 根据库返回的推荐权限列表请求运行时权限
- 启动和停止一个持续扫描的 Flow

构建：

```bash
./gradlew :sample:assembleDebug
```

安装后进入首页，可以直接看到：

- 当前 client / adapter / 权限摘要
- bonded devices 可点击列表
- connected peripherals 可点击列表
- 一个可直接测试的设备级连接面板
- 一个可直接测试的自动重连面板
- 一个可点击选择的最近扫描结果列表
- 持续更新的事件日志与扫描结果
