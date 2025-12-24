# Virtual Display（Shower）迁移包

本目录用于把 Operit 里的「虚拟屏 / VirtualDisplay」相关能力整理成可复制到其它 Android 项目的材料包。

包含内容：

- `android/showerclient/`：宿主侧使用的最小客户端库（启动 server、接收 Binder、创建虚拟屏、输入注入、视频渲染）
- `android/shower-server-app/`：Shower server 源码（`com.ai.assistance.shower.Main` 等），用于理解/二次开发
- `tools/shower_ws_client.py`：PC 侧调试用 WebSocket client（可选）
- `docs/`：接入文档

从零接入请先看：`virtual-display/docs/INTEGRATION.md`。
