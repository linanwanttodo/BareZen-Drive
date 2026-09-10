# BareZen-Drive 文档索引

本目录收录 BareZen-Drive 的架构、API、开发与路线图文档。当前版本 v0.0.1。

## 文档列表

- [architecture.md](architecture.md)：总体架构、模块布局、服务端与客户端结构、数据模型、上传协议、客户端国际化（i18n）与部署说明。
- [api.md](api.md)：v0.0.1 的 REST API 参考，覆盖认证、文件夹、文件、上传、缩略图、签名链接、相册与公开分享，含错误码全集。
- [development.md](development.md)：本地开发指南，包含 JDK 与 Android SDK 要求、服务端与客户端运行命令、测试命令以及一键部署步骤。
- [roadmap.md](roadmap.md)：v0.0.1 已完成范围、v0.2 进行中的条目、v0.3 及以后规划，以及已知技术债。
- [superpowers/specs/2026-09-02-barezen-drive-v0.0.1-design.md](superpowers/specs/2026-09-02-barezen-drive-v0.0.1-design.md)：v0.0.1 设计文档，记录范围、架构、数据库、API、上传协议、客户端、测试与部署的原始设计。
- [superpowers/specs/2026-09-05-netdisk-core-experience-design.md](superpowers/specs/2026-09-05-netdisk-core-experience-design.md)：网盘核心体验设计文档，记录缩略图、相册、预览、导航与分享链接等 v0.2 设计的来源。
- [superpowers/plans/2026-09-02-barezen-drive-v0.0.1-implementation.md](superpowers/plans/2026-09-02-barezen-drive-v0.0.1-implementation.md)：v0.0.1 实施计划，按任务拆分 v0.0.1 的落地步骤。
- [superpowers/plans/2026-09-05-netdisk-core-experience-implementation.md](superpowers/plans/2026-09-05-netdisk-core-experience-implementation.md)：网盘核心体验实施计划，按任务拆分 v0.2 的落地步骤。

## 建议阅读顺序

1. 先读仓库根目录的 [README.md](../README.md)，了解产品定位、功能特性、模块表与快速开始。
2. 再读 [architecture.md](architecture.md)，建立整体架构与数据模型的概念。
3. 需要对接接口时读 [api.md](api.md)。
4. 需要搭建环境或部署时读 [development.md](development.md)。
5. 需要了解版本范围与后续计划时读 [roadmap.md](roadmap.md)。
6. 需要追溯设计决策时，再按需查阅 superpowers 下的设计文档与实施计划。
