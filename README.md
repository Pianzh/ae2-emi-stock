# AE2 EMI Stock

EMI × AE2 的附属模组，模仿 [GTO 整合包](https://github.com/GregTech-Odyssey) 的**直接下单**：不开终端，在 EMI 里就能看到 ME 网络的库存，按中键直接下单合成。适用于 Minecraft 26.3 / NeoForge 26.3.0.x，客户端和服务端都要装。

> **注意**：本模组由 AI 辅助编写。功能经过游戏内实测，但不保证稳定性，也不承诺持续维护。使用前请备份存档，风险自负。

> 兼容性只保证 AE2 `26.3.2-alpha`、EMI `2.x` 这一组合，其余版本未测试。官方 EMI 尚未发布 26.3 版本，26.3 下实际使用的是 [PF EMI](https://www.curseforge.com/minecraft/mc-mods/pf-emi) 的构建。

## 功能

### EMI 库存角标

身上带着已绑定、能连上 ME 网络的无线终端（含 AE2WTLib 各类无线终端，背包或饰品栏均可）时，EMI 侧栏的物品右上角会显示网络库存：

- 绿色数字 — 实际库存量（1.2K 这种缩写格式）
- 蓝色 `+` — 库存为 0，但网络里有对应合成样板
- 两种终端同时带时，先取背包里的

搜索结果、收藏栏、查询历史、合成历史都支持。

### 同步机制

参考 AE2 终端的做法：第一次打开界面全量同步，之后每 6 tick 只推变化量。翻页、搜索、切换收藏都是即时的，不再逐页查询。断线或换网络后自动清空重来。

### 不开终端下单（模仿 GTO）

按 `E` 打开背包（创造模式物品栏也支持），对着 EMI 里有样板的物品按中键，直接弹出 AE2 原生的下单数量窗口——终端里能下的单，这里都能下。这就是 GTO 那套"在 EMI 里直接下单"的体验。

下单走服务端校验：无线终端是否绑定、有没有电、在不在连接范围内，全部按 AE2 原生逻辑检查，客户端绕不过去。

## 安装

从 [Releases](https://github.com/Pianzh/ae2-emi-stock/releases) 下载 jar，客户端和服务端 `mods` 各放一份，两端版本需要一致。服务端没装或版本旧时，客户端会退回逐页查询模式，但无法下单。

## 致谢

- 全量同步 + 增量更新复用了 AE2 的 `IncrementalUpdateHelper` 和 `MEInventoryUpdatePacket` 编码。
- 下单入口是 AE2 原生的 `CraftAmountMenu`，界面和终端内下单完全一致。
- 交互设计模仿了 [GTO 整合包](https://github.com/GregTech-Odyssey) 的 EMI 直接下单，与本项目无隶属关系。

## License

[MIT](LICENSE)
