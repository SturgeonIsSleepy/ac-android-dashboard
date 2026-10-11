# 0.9.1 桥接端口生命周期修复

日期：2026-10-11

现场问题：UDP 9876 和 TCP 9877 仍标记已退出的桥接 PID，进程列表中已无该进程，UDP 心跳也不响应。停止 ADB 服务后两个监听端口同时释放，确认长期存活的子进程保留了监听句柄。旧测试版因此无法重新绑定端口

修复将 UDP 监听、TCP 监听及接受的 TCP 连接设为不可继承，避免 ADB 子进程保留桥接资源。每个 Windows 登录会话使用同一个互斥锁，来自不同路径的重复启动只提示已有监视器并退出，不关闭当前实例或影响游戏

Windows C# 回归检查先构造可继承的 UDP/TCP 监听句柄，启动与 USB 通道同样使用标准流重定向的子进程。旧行为中父端关闭后无法绑定端口；应用修复后，子进程仍存活时两个端口可以立即重新绑定

完整桥接进行了三轮启动、不同路径重复启动、UDP 心跳与 TCP 主数据序列检查、强制结束和重开。每轮重复启动返回成功且原实例继续传输；结束后 UDP 9876、TCP 9877 可重新绑定，实际 ADB 服务 PID 保持不变。测试没有启用演示模式或注入模拟游戏数据

本次安卓 APK 保持 versionName 0.9.0、versionCode 15，未重新安装。当前游戏未运行，因此上述检查验证连接与资源释放，不代表重新完成驾驶、后视图或完圈验证。既有实机驾驶验证保留在 0.9.0 记录中

复现与回归命令：

```powershell
& "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /out:artifacts\BridgeSocketLifetimeCheck.exe tests\BridgeSocketLifetimeCheck.cs bridge\BridgeSockets.cs
& .\artifacts\BridgeSocketLifetimeCheck.exe
python tests\bridge_lifetime_check.py --bridge .\ACFlipBridge.exe --adb "你的 platform-tools\adb.exe"
```

完整生命周期检查应在关闭桥接后执行，结束后需要重新打开连接监视器
