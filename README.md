# T-Dash — 非官方 Tesla 安卓伴侣（BLE-only）

T-Dash 是一个**纯蓝牙直连**的 Tesla 安卓应用：仪表、控制、统计与一整套行车工具全部通过
BLE 与车辆直接通信，不经过任何服务器——没有开发者账号、没有车主账号登录、没有订阅，
也没有遥测。命令层基于 Tesla 官方开源库
[teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command)
定义的 Vehicle Security / Infotainment 协议，用 Kotlin 从零实现；`.proto` 定义取自官方仓库。

> **免责声明**：本项目与 Tesla, Inc. 无隶属关系，仅供个人车辆管理与学习使用。
> 使用即表示你理解：向车辆发送指令属于你自己承担风险的操作。

## 能力边界（先读这个）

- **手机需在车旁（约 5 m 内）**：所有功能都依赖 BLE 直连；远程控制在当前版本不提供
  （协议层已预留 Fleet API 的 HMAC 授权模式，未启用）。
- 车机屏幕自带的界面无法被第三方 app 绘制；本 app 的「仪表」页是**手机侧**数字仪表。
- 唯一的联网点：统计页轨迹的高德地图瓦片（osmdroid，磁盘缓存）。车辆链路全程 BLE。
- 首次配对需要**实体 NFC 钥匙卡**：发送配对请求后把卡贴在中控读取区，并在车机屏幕确认。
- 兼容性：协议要求车机固件 ≥ 2023.38；命令响应加密需要 ≥ 2024.38（旧固件自动回落明文）。

## 功能一览

- **仪表（首页）**：大数字车速（按速度分档配色、可与车机口径对齐）、功率弧、阈值轨、
  档位；P 档切换驻车版面（时钟 + 电量/车外/空调卡）；「接续上一段路程」浮层；
  行驶中 D/R 档息屏/后台仍保持监听，P 档自动安静。
- **控制**：锁/后备箱/前备箱/充电口/车窗/空调/充电/哨兵/唤醒/鸣笛闪灯。按钮可用性跟随
  车辆真实状态（已解锁禁「解锁」、行驶中禁开闭件、未插枪禁充电启停）；显示最后一次读到的
  状态而非空白，命令失败原因就地显示。
- **工具**：快捷启动（拉起高德等常用 App）、事故处理（定位报址 + 拍照取证清单 + 一键
  导出 A4 取证 PDF）、行车记录仪（读 U 盘 TeslaCam，事件列表、1–4 路同步回放、
  「跳到触发时刻」、2×2 合成导出并烙时间码）、零百计时（250 ms 车速流插值，±0.05 s）、
  车机皮肤（图片压缩写 U 盘 Wraps，车机直接应用）。
- **统计**：车速/功率实时曲线（本会话全量保留）、按速度着色的 GPS 轨迹地图、
  行程切换与叠加、本地 JSON 持久化（崩溃恢复）。
- **设置**：连接兜底（手动 MAC）、高德 Key（仅事故助手地址翻译用）、速度分档与五段
  配色自定义（±1 km/h 步进、8 色色板）、本机钥匙与缓存管理、开发者模式。

## 延迟为什么低

| 通道 | 典型延迟 | 依赖 |
| --- | --- | --- |
| **BLE 直连（唯一通道）** | **~50–300 ms** | 无 |

- **会话缓存**：握手结果（epoch/counter/域公钥）落盘，重启后首条命令省一次往返。
- **VCSEC 免唤醒**：锁车/解锁/门锁状态走车身安全控制器，整车休眠也秒回。
- **行驶期带宽分配**：车速道独占快路径（250 ms，单次尝试短超时），慢车道在行驶中静默，
  慢路径失败不阻塞车速。
- 控制页显示每条命令的实测毫秒延迟。

## 构建与安装

```bash
./gradlew :app:assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 官方协议测试向量（ECDH/HMAC/TLV/GCM 等）
```

- 要求：JDK 17、Android SDK（platform 35 + build-tools 35.0.1）。`local.properties` 里
  配你本机的 `sdk.dir`（不入库）。
- Gradle wrapper 与依赖仓库默认配置了国内镜像（阿里云/腾讯），海外环境可自行改回官方源。
- 手机端开启蓝牙与定位权限（扫描需要 `ACCESS_FINE_LOCATION`，这是 Android 的要求，
  app 不做任何位置上传——位置只用于本机轨迹）。

## 代码结构

```
app/src/main/proto/             官方 .proto（universal_message / vcsec / car_server）
app/src/main/java/com/teslalink/app/
  protocol/                     Vehicle Command 协议核心
    Metadata.kt                 TLV 元数据序列化
    Crypto.kt                   P-256 ECDH、AES-GCM、HMAC-SHA256
    Session.kt                  命令签名/响应解密、会话导出恢复
    Dispatcher.kt               会话管理、请求-响应匹配、缓存与重同步
    Errors.kt                   错误分类、重试判定、防重放滑动窗口
  ble/BleTransport.kt           GATT 扫描/连接/MTU/分帧
  vehicle/                      载荷构造与高层命令（重试、唤醒、配对、遥测）
  store/                        AndroidKeyStore 密钥保管、DataStore 偏好与会话缓存
  vm/                           LinkViewModel / DashboardViewModel / TripRecorder / ...
  ui/                           Compose 界面（控制/工具/仪表/统计/设置/调试）
  tools/                        记录仪扫描与合成导出、取证 PDF、皮肤写入、坐标转换
app/src/test/                   官方协议测试向量移植（无车也能验证协议实现）
```

## 隐私

- 私钥（P-256）由 AndroidKeyStore 加密后仅存本机；配对只把公钥交给车。
- 行程轨迹、取证照片、个人信息全部只存本机；删除操作不触碰系统相册文件。
- 日志只出现密钥指纹末四位。

## Acknowledgements

- [teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command)（Apache-2.0）
  ——协议定义与 `.proto` 文件来源。
- [esphome-tesla-ble](https://github.com/esphome/tesla-ble) 及其社区分支（AGPL-3.0）
  ——仅作为调试期间的协议行为参照，未复制其代码；本项目的协议实现为独立 Kotlin 实现。

## License

[Apache License 2.0](LICENSE)
