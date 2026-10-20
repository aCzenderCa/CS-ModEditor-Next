# 测试夹具约定

本目录是数据层测试的只读夹具，不依赖本机安装的游戏。

```
fixtures/
├── minipack/                 # 迷你数据包：与 csti / csff 相同的目录约定
│   ├── PackInfo.json
│   ├── TypeStruct/<Type>.json
│   ├── TypeStruct/Enum/<Enum>.json
│   ├── BaseJson/<Type>.json
│   ├── Doc/<Type>.yml
│   ├── Doc-En/<Type>.yml
│   ├── Objects/<Type>/...    # CardData 按 CardType 分子目录
│   ├── GUID/...
│   ├── Name/...
│   └── SimpCn.csv
├── probepack/                # 校验分支夹具：基础类型/枚举/对象/标量引用/引用列表
│   ├── TypeStruct/Probe.json
│   ├── BaseJson/Probe.json
│   ├── TypeStruct/LocalizedString.json
│   ├── TypeStruct/Enum/CardTypes.json
│   ├── GUID/CardData.json
│   └── Name/CardTag.json
└── mods/
    ├── manifest.json         # 样例 Mod 清单（framework / game / path）
    ├── ml-sample/            # ML 目录型 Mod
    └── mc-sample/            # MC 目录型 Mod
```

约定：

1. 夹具中的 GUID 使用重复数字便于肉眼识别：`1111…` 为 MiniKnife，`2222…` 为 MiniPerk，
   `3333…` 为 ML Mod 对象，`4444…` 为 MC Mod 对象。
2. 不写入游戏本体真实数据；需要真实数据包的测试通过
   `TestFixtures.cstiPack` / `TestFixtures.csffPack` 获取路径，找不到时跳过。
3. 真实数据包路径优先级：
   `-Dcsmode.pack.csti=...` → 环境变量 `CS_MODEDITOR_PACK_CSTI` → 默认 Steam 安装路径。
4. 新增样例 Mod 时同步更新 `mods/manifest.json`，测试会校验清单项与目录是否一致。
5. 样例 Mod 的元数据包含未知字段（`CustomMeta` 等），用于验证读写往返不丢字段；
   `mc-sample/Data/DataObjectModify/CardTag/FixtureMc@tag_McFixture.json` 用于验证
   `@` 代替 `:` 的文件名与工程内目标解析。
