# 现有方案调查

调查日期：2026-10-07

## SIM Dashboard

[开发者官网](https://www.stryder-it.de/simdashboard/)提供 Android/iOS 移动仪表，官方列出 Assetto Corsa 支持，是成熟的现成候选

本次需要自行调整外屏布局、生命周期和数据刷新，并交付可继续修改的项目。未找到能直接改造该产品的公开源代码，因此没有购买、反编译或重新打包它

## SimHub

[官方手机访问文档](https://github.com/SHWotever/SimHub/wiki/Troubleshoot-Dashstudio-Web-access)展示浏览器访问仪表的方式。此方式不满足本次“外屏最终显示不能是浏览器页面”的约束，故未用于显示端

## Assetto Corsa Shared Memory Library

[mdjarv/assettocorsasharedmemory](https://github.com/mdjarv/assettocorsasharedmemory)为 MIT 授权的 C# 经典版 AC 共享内存库，已有物理、图形和静态信息结构

固定上游提交：`dc39bfacf0a70e415eaefdfc4743780e12b9f2f4`

下载源码保存在 `vendor/assettocorsasharedmemory/`，未改动上游文件。桥接编译直接使用其 `Physics.cs`、`Graphics.cs` 和 `StaticInfo.cs`。读取入口使用只读共享内存映射，约 60 Hz 统一采样，不采用上游不同页面的默认事件定时频率

新增的适配层负责 UDP 协议、赛道 AI 路线和 Android 原生显示，不需往游戏目录安装插件

## 真机外屏入口

已通过 ADB 核实 vivo V2256A，Android 14，外屏 `displayId=1`、682 × 422、300 dpi、60 Hz

已安装“超级小副屏”包名为 `cn.pashiguoke.subscreen`，版本 `1.5 正式版`。公开搜索结果对该包名的旧版描述不可靠，本次使用设备上 APK 标签、系统显示信息和实际界面核对

其外屏初始网格只显示系统快捷应用，上滑才进入完整应用列表。“AC 外屏仪表”已出现在列表首项，可直接点击启动

仪表声明可调整窗口大小，不固定主屏方向。布局根据实际 View 尺寸绘制，在 682 × 422 窗口内填满画面，并预留外屏圆角空间

## 联调修正

Windows 默认计时精度曾令发送频率只有约 34 Hz，启用运行期间的 1 ms 计时分辨率后测得约 60 Hz，正常退出释放计时请求

探测客户端退出曾触发 Windows UDP `WSAECONNRESET`，导致桥接接收循环退出。桥接设置 `SIO_UDP_CONNRESET` 禁止把 ICMP 端口不可达作为整个监听端的连接重置。参考 [Microsoft Winsock IOCTL 文档](https://learn.microsoft.com/en-us/windows/win32/winsock/winsock-ioctls)

## 0.3 游戏 HUD 参考

地平线默认按 Forza Horizon 5 理解，检索 [Forza 官方论坛中的实际驾驶截图](https://forums.forza.net/t/fh5-fm8-wheels-problem/625523)，提取环形转速、指针、洋红色高转速区域、倾斜数字与车速、挡位层级

计时主要依据用户提供的 AC 实际游戏截图，并读取本机已安装的 F12020Leaderboard 源码核对结构。采用矩形转播牌、排名和车手栏、左右计时区及底部分段；不把截图中的三段固化到实现

车况检索 [EA F1 25 无障碍与 OSD 资源](https://www.ea.com/able/resources/f1-25)及游戏磨损界面资料。按用户指定采用四个实色方块、百分比与俯视车身；胎温另作更醒目的 cold/hot 提示

路书检索 [WRC 9 官方产品说明](https://www.nacongaming.com/en-US/wrc-9-world-rally-championship-deluxe-edition-pc-digital)和 [WRC 9 游戏画面](https://www.ppe.pl/recenzje/209589/wrc-9-the-official-game--recenzja-i-opinia-o-grze-ps4-xone-pc.html)。采用彩色等级牌、白色实心箭头、独立距离和后续牌队列

部分公开原图在本次浏览器中加载受阻。界面为原生矢量重绘，使用系统字体并为 682 × 422 外屏调整尺寸，不能称为原版素材或像素级一致的复刻

## 0.4 转速与 F1 DRS

读取本地当前车辆 `data.acd` 的 `analog_instruments.ini`，Maserati Alfieri 的 RPM LUT 为 0、4000、6000、8000、9000。共享内存最高转速与发动机 `LIMITER=7500` 一致。因此量程取 9000，红线定位 7500，不再使用固定百分比

车辆配置读取参考 [acd.py 1.0.0](https://github.com/philippkosarev/acd.py)，只读移植到桥接，保留 GPL-2.0 许可和对应源代码。没有向游戏目录写入或解包文件

DRS 显示行为参考 [EA F1 25 官方技巧中的 HUD 提示](https://www.ea.com/games/f1/f1-25/news/f1-25-tips-and-tricks)，两侧向中间的绿色进度与开启后淡出按用户指定实现。距离依据本机赛道 `drs_zones.ini` 的 START/END 归一化区间及共享内存赛道长度计算；区间内无游戏开启资格时不报告为可用
