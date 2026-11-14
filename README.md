# quizgate-server

儿童防沉迷答题闸门的服务端。孩子连续玩娱乐应用满 N 分钟 → 设备端抢屏出一道学科题 → 答对才放行；
家长在浏览器后台传题、看用量、收"闸门被人关掉"的告警。

协议、客户端、后台三边只有一份事实源：`proto/quizgate.proto`。

- 孩子端 App：https://github.com/yaomoshen520/quizgate-android
- 家长后台：https://github.com/yaomoshen520/quizgate-console

## 传输形状

HTTP POST + `Content-Type: application/x-protobuf`，请求体与响应体都是裸 protobuf 字节，**没有 JSON 层**。
不装 `HttpMessageConverter`：那需要反射拿 parser，正是 GraalVM 原生镜像会炸的地方。
令牌在请求头 `X-Quizgate-Token`。

路由档位看令牌 kind，数据范围看数据库里的 role 列，两者都在服务端判。
前端的路由守卫只是体验，挡不住手写请求。

- `/api/device/**` 要 `device` 令牌：`enroll` / `heartbeat` / `gate-event`
- `/api/parent/**` 要 `parent` 令牌，且所有读都按账号过滤
- `/api/admin/**` 只放管理员：发注册码、重置口令、建孩子

判分凭据是服务端的 `question_ask` 台账：没下发过的题一律不回判定结果，这个接口不是答案预言机。

`next-question`、`answer`、`usage` 还要求设备已绑定孩子。未绑定时返回 400，孩子端使用本地题并保留
用量队列等待绑定后重传，不能把用量确认后写到 `child_id=0`，也不能让未绑定设备共享判分账本。

## 当前进展与待确认

三端已完成项、尚未实现／验证的内容及待用户确认规则，统一记录在
[项目进展与待确认](docs/项目进展与待确认.md)。游戏模式的已确认规则及任务详见
[游戏模式需求与任务](docs/游戏模式需求与任务.md)。首版代码和自动化检查已完成，实际游戏／真机验收待完成。

App 本机日志、性能采样的实现与验证范围，以及自动更新的待确认事项见
[App 更新与诊断任务](docs/App更新与诊断任务.md)。

题库按钮修复及此前 2D/六只形象方向见[虚拟形象与按钮优化需求](docs/虚拟形象与按钮优化需求.md)。
新的九种 3D 萌宠程序版已在 [avatar-demo](avatar-demo/README.md) 实现换装、年级灵感、动作和本机保存；
可独立静态运行，不需要重建 Android。正式资产、App 接入与奖励规则见[虚拟宠物形象需求](docs/虚拟宠物形象需求.md) V1—V6，待确认部分保持现状。

## 跑起来

口令只走环境变量，仓库里没有默认口令；用户名留空就不建号。

```
export QUIZGATE_ADMIN_USERNAME=...   QUIZGATE_ADMIN_PASSWORD=...
export QUIZGATE_PARENT_USERNAME=...  QUIZGATE_PARENT_PASSWORD=...
./mvnw spring-boot:run
```

拍照识题默认关死（`QUIZGATE_EXTRACTOR_ENABLED=false`）。打开才走云端多模态 API，
识别出来的题**当场入库但状态是"未核对"**，而闸门抽题的唯一入口带 `verified = true` 过滤——
**家长没核对过的题，孩子永远抽不到**。图片字节只活在一个方法栈里，全程不落库。

构建要走 `./mvnw.cmd`，protobuf-maven-plugin 要求 Maven ≥ 3.9.6，系统里老的 3.8.x 会在 generate 阶段直接失败。

Windows 的 JVM 构建可用 `./mvnw.cmd test package`，产物为 `target/anti-addiction-0.0.1-SNAPSHOT.jar`，
包含先前构建好的 `web/` 静态文件。Maven 分发下载慢时可设置环境变量
`MVNW_REPOURL=https://maven.aliyun.com/repository/public`；依赖镜像用自己的 `settings.xml` 配置并通过 `-s` 传入。

## 原生镜像

改过依赖或安全链之后必须重新出包并跑一遍 `native-smoke.sh`：真正的风险不是编不过，
而是"编得过、JVM 测试全绿、原生二进制一收请求就炸"。

```
cd web && npm run build          # 产物写进 src/main/resources/static，镜像烘焙的是那一刻的文件
cd .. && cmd /c native-build.bat # 约 2 分钟
target/anti-addiction.exe --spring.datasource.url=jdbc:h2:file:./data-native-smoke
# 冒烟脚本读同一批环境变量建号；本机把它们放在 .smoke-env（不入库），克隆出来就自己 export
bash native-smoke.sh
```

`web/` 是另一个仓库（quizgate-console），本机的布局是把它检出在这个仓库目录里的 `web/`，
因为它的前端源生成和产物路径都按相对路径找 `../proto` 与 `../src/main/resources/static`。
单独检出时那两个相对路径要改，细节见那边 README 的最后一段。

前端产物不入库（`/src/main/resources/static/` 已在 `.gitignore`），就是为了不许出现两份真：
烤进仓库的 dist 会让"改了源码忘了重编"这种过期静默通过冒烟。
出包与 `npm run build` 有竞态——native-image 在扫 static 时重跑构建，镜像里就是半真半假，
所以顺序固定：先构建完，再出包。

冒烟脚本里的断言按序列化之后的真实长相写：proto3 的 `bool=false` 与 `int32=0` 是默认值，文本格式压根不打印那一行。

## 这台产品做不到什么

- **卸载拦不住**：普通 App 结构上无法上报自己的卸载。服务端只能说"这台设备超过 N 分钟没上报"，
  原因给不出——卸载、关机、断网在这一条上长得一模一样。
- **无障碍开关被关掉只能事后知道**：看门狗（前台服务）负责当场发现并记一条事件，它只报不拦。
- 事件投递通道只有后台顶部红条 + 告警列表，本项目没有任何邮件/短信出口，
  所以找回密码 = 管理员重置，家长侧没有自助找回。
