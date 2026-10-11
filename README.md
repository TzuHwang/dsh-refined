# 🏰 Millénaire Refined | 千年村庄精修版

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.4.3-orange.svg)](https://files.minecraftforge.net)
[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://openjdk.java.net)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)
[![Development](https://img.shields.io/badge/Status-Alpha%20Development-red.svg)](#development-status)

[English](#english) | [中文](#中文)

---

## English

### 📖 About

**Millénaire Refined** is a fork of [JasonCian/Millenaire-rewrite](https://github.com/JasonCian/Millenaire-rewrite), continuing a complete modernization of the classic Millenaire mod for Minecraft 1.20.1. This project brings the beloved village-building and cultural experience to modern Minecraft with enhanced performance, updated mechanics, and a cleaner codebase.

Originally created as one of Minecraft's most ambitious civilization mods, Millenaire adds living, breathing villages with unique cultures that grow and evolve over time. This project preserves the core spirit while embracing modern modding standards.

~~btw,I did not find the open source code library for 1.12. The reference code of the project is forked from https://github.com/MoonCutter2B/Millenaire seems to be version 1.8.~~

thanks https://github.com/Leviaria/Millenaire-Reborn, I found 1.12 version open source
now I'll rewrite based 1.12

### ✨ Key Features

#### Core Systems

- **Advanced Currency System**: Three-tier currency with copper, silver, and gold deniers
- **Complete Bilingual Translation**: 450+ English + 450+ Chinese entries, all creative tabs and items fully translated
- **Creative Mode Tabs**: 9 civilization tabs (Norman, Byzantine, Indian, Japanese, Mayan, Seljuk, Inuit, + Blocks, Food, Misc)
- **Decorative Blocks**: Stone, wood, earth, and culture-specific building materials
- **Magic Items**: Dynamic amulets, wands, and the legendary Amulet of Creation

#### Item Systems (225+ Items!)

| Category | Count | Description |
|----------|-------|-------------|
| **Building Materials** | 30 | Culture-specific blocks and crafting materials |
| **Quest Items** | 8 | Creation Quest storyline items + Galianite Ore |
| **Trade Goods** | 14 | Dyes, spices, and luxury items for each culture |
| **Loot Items** | 8 | Bandit camp and ruins exploration loot |
| **Tools & Weapons** | 40+ | Culture-specific equipment sets |
| **Food & Crops** | 45+ | Unique cuisine for each civilization |
| **Magic & Amulets** | 10+ | Dynamic magical items with special abilities |
| **Parchments** | 26 | Knowledge scrolls for each culture |

#### 🏘️ Living Villages

- **Dynamic Growth**: Villages expand organically based on population and resources
- **Cultural Diversity**: 7 unique civilizations with distinct building styles
- **Economic Systems**: Complex trade networks and currency exchange
- **Social Interactions**: Deep villager relationships and reputation systems

#### 🎨 Cultural Systems

All 7 cultures fully implemented with complete item sets:

| Culture | Building Materials | Trade Goods | Food | Tools/Armor |
|---------|-------------------|-------------|------|-------------|
| **Norman** | Timber Frame, Wattle & Daub, Plaster | Woad Dye | Cider, Calvados, Tripes | Full set |
| **Byzantine** | Byzantine Tile, Marble | Tyrian Purple, Murex Shell | Wine, Feta, Souvlaki | Full set |
| **Indian** | Mud Brick, Cooked Brick | Indigo, Saffron | Curry, Rasgulla | Brick Mould |
| **Japanese** | Thatch, Washi Paper | - | Sake, Udon, Ikayaki | 3 armor tiers! |
| **Mayan** | Limestone, Obsidian | Cochineal Dye | Cacauhaa, Masa, Wah | Obsidian tools |
| **Seljuk** | Glazed Tile, Carved Stone | Sumac, Rose Water | Pide, Helva, Lokum | Scimitar, Bow |
| **Inuit** | Whale Bone, Packed Snow | - | Bear/Wolf meat, Stews | Fur armor, Ulu |

#### 🔮 Magic & Technology

- **Dynamic Amulets**: Color-changing items that respond to environment
  - Alchemist Amulet: Detects nearby ores
  - Vishnu Amulet: Senses nearby creatures
  - Yggdrasil Amulet: Shows altitude information
  - Skoll & Hati Amulet: Controls day/night cycle
- **Amulet of Creation**: Legendary quest reward with passive regen and powerful buffs
- **Wands**: Summoning, Negation, Creative, and Tuning Fork
- **Advanced Crafting**: Culture-specific recipes and materials

### 🚀 Development Status

**Current Version**: `0.1.4-alpha` (Forge 1.20.1)
**Last Updated**: January 17, 2026

#### ✅ Completed Systems

| System | Items | Status | Description |
|--------|-------|--------|-------------|
| **Item System** | 225+ items | ✅ Complete | All cultural items, tools, food, materials registered |
| **Building Materials** | 30 items | ✅ Complete | Culture-specific building blocks and materials |
| **Quest System** | 8 items | ✅ Complete | Creation Quest items with special Amulet of Creation |
| **Trade Goods** | 14 items | ✅ Complete | Dyes, spices, luxury items for trading |
| **Loot System** | 8 items | ✅ Complete | Exploration rewards for lone structures |
| **Currency System** | 3 tiers | ✅ Complete | Copper, Silver, Gold deniers |
| **Food System** | 45+ foods | ✅ Complete | Culture-specific cuisine with effects |
| **Magic Items** | 10+ items | ✅ Complete | Amulets, wands with special abilities |
| **Block System** | 35 blocks | ✅ Complete | Building blocks, ores, functional blocks |
| **Translation** | 450+ entries | ✅ Complete | English/Chinese for all content |
| **Creative Tabs** | 9 tabs | ✅ Complete | All cultures + utility tabs |

#### 🔄 In Progress

| System | Progress | Notes |
|--------|----------|-------|
| **Village Core System** | 30% | Core village logic |
| **Building Framework** | 15% | Construction system |
| **Entity System** | 10% | NPC villagers |
| **Loot Tables** | 0% | Integration with loot items |

#### 📋 Planned Features

- **Mill Chest System**: Secure storage with village integration
- **Path & Road System**: Cultural building connections
- **Quest Storyline**: Full Creation Quest implementation
- **AI Villagers**: Smart NPCs with complex behaviors
- **Building Generator**: Procedural architecture system
- **World Generation**: Galianite ore spawning, lone structures

### 🎯 Technical Achievements

#### 🏗️ Modern Architecture

- **Package Structure**: Clean `com.jasoncian.millenaire_rewrite` organization (package and mod ID `millenaire_rewrite` are kept from upstream for save compatibility)
- **Type Safety**: Full generic system for variant blocks and items
- **Data Generation**: Automated resource file generation (200+ files)
- **Localization**: Complete English/Chinese translation support
- **Creative Tabs**: All cultures implemented and categorized

#### 📊 Project Statistics

- **Java Classes**: 140+ modern implementations
- **Texture Assets**: 950+ high-quality PNG files
- **Generated Resources**: 180+ automatically created files
- **Registered Items**: 225+ unique items with proper integration
- **Registered Blocks**: 35 blocks with full variant support
- **Translation Keys**: 450+ bilingual entries

#### 🔧 Code Quality

- **Modern Java 17**: Latest language features and best practices
- **Forge 47.4.3**: Current stable API implementation
- **Clean Architecture**: Modular design for easy expansion
- **Comprehensive Documentation**: Full JavaDoc coverage

### 🛠️ Installation & Development

#### Prerequisites

- **Minecraft**: 1.20.1
- **Forge**: 47.4.3+
- **Java Development Kit**: 17+
- **IDE**: IntelliJ IDEA or Eclipse with Minecraft Development Kit

#### Quick Start

```bash
# Clone the repository
git clone https://github.com/TzuHwang/dsh-refined.git
cd dsh-refined

# Setup development environment
./gradlew genEclipseRuns  # For Eclipse
./gradlew genIntellijRuns # For IntelliJ

# Run in development
./gradlew runClient
```

#### Building

```bash
# Build mod JAR
./gradlew build

# Generate data files
./gradlew runData
```

### 🤝 Contributing

We welcome contributions! Here are the current development priorities:

#### Current Priorities

1. **Village Management System** - Core village logic and data structures
2. **Building Generation** - Automated construction system
3. **NPC AI System** - Smart villager behaviors
4. **Loot Table Integration** - Connect loot items to world generation

### 📜 License

This project is licensed under the MIT License - see [LICENSE.txt](LICENSE.txt) for details.

### 🙏 Acknowledgments

- **Original Millenaire Team** - For creating the beloved original mod
- **[JasonCian](https://github.com/JasonCian/Millenaire-rewrite)** - For the Millenaire Rewrite 1.20.1 port this project is based on
- **MinecraftForge Team** - For providing the modding framework
- **Community Contributors** - For feedback and suggestions

---

## 中文

### 📖 关于项目

**千年村庄精修版**（Millénaire Refined）fork 自 [JasonCian/Millenaire-rewrite](https://github.com/JasonCian/Millenaire-rewrite)，是经典千年村庄（Millenaire）模组在 Minecraft 1.20.1 上的完全现代化重制。本项目将备受喜爱的村庄建设和文化体验带到现代 Minecraft 中，具有增强的性能、更新的机制和更清洁的代码库。

作为 Minecraft 最具雄心的文明类模组之一，千年村庄添加了具有独特文化的活跃村庄，这些村庄会随时间增长和演化。本项目保留了核心精神，同时拥抱现代模组开发标准。

~~提一嘴，我没有找到 1.12 的开源代码库，项目的参考代码 fork 自https://github.com/MoonCutter2B/Millenaire
貌似是 1.8 版本~~
谢谢这个写https://github.com/Leviaria/Millenaire-Reborn的哥们儿，我在他代码里找到1.12的源码
之后我会基于 1.12 去重做这个 mod 了

### ✨ 核心特性

- **三层货币系统**：铜、银、金第纳尔
- **完整双语翻译**：450+ 条英文+450+ 条中文，所有创造标签和物品均已翻译
- **创造模式标签页**：9 个文明分类（诺曼、拜占庭、印度、日本、玛雅、塞尔柱、因纽特 + 方块、食物、杂项）
- **装饰方块系统**：石材、木材、土质及文化特色建筑材料
- **魔法物品**：动态护符、法杖及传奇创世护身符

#### 物品系统（225+ 物品！）

| 类别 | 数量 | 描述 |
|------|------|------|
| **建筑材料** | 30 | 文化特色方块和制作材料 |
| **任务物品** | 8 | 创世任务线物品 + 加里亚奈特矿石 |
| **贸易品** | 14 | 各文化的染料、香料和奢侈品 |
| **战利品** | 8 | 强盗营地和遗迹探索奖励 |
| **工具武器** | 40+ | 文化特色装备套装 |
| **食物作物** | 45+ | 各文明独特料理 |
| **魔法护符** | 10+ | 具有特殊能力的动态魔法物品 |
| **羊皮纸** | 26 | 各文化知识卷轴 |

#### 🏘️ 活跃村庄

- **动态增长**：村庄根据人口和资源有机扩张
- **文化多样性**：7 个独特文明，各具特色建筑风格
- **经济系统**：复杂的贸易网络和货币交换
- **社交互动**：深度的村民关系和声望系统

#### 🎨 文化系统

全部 7 种文化已完整实现：

| 文化 | 建筑材料 | 贸易品 | 食物 | 工具/盔甲 |
|------|----------|--------|------|-----------|
| **诺曼** | 木框架、编条夯土、灰泥 | 菘蓝染料 | 苹果酒、卡尔瓦多斯 | 完整套装 |
| **拜占庭** | 拜占庭瓷砖、大理石 | 泰尔紫、骨螺壳 | 葡萄酒、羊奶酪 | 完整套装 |
| **印度** | 泥砖、烧制砖 | 蓝靛、藏红花 | 咖喱、甜点 | 砖模 |
| **日本** | 茅草、和纸 | - | 清酒、乌冬面 | 3 级盔甲！ |
| **玛雅** | 石灰石、黑曜石 | 胭脂红染料 | 可可饮品、玉米饼 | 黑曜石工具 |
| **塞尔柱** | 琉璃瓦、雕刻石 | 漆树果、玫瑰水 | 土耳其薄饼、软糖 | 弯刀、弓 |
| **因纽特** | 鲸骨、压实雪 | - | 熊肉、狼肉、炖菜 | 毛皮盔甲、乌卢刀 |

#### 🔮 魔法与科技

- **动态护符**：根据环境改变颜色的物品
  - 炼金术士护符：探测附近矿石
  - 毗湿奴护符：感知附近生物
  - 世界树护符：显示高度信息
  - 斯库尔与哈提护符：控制昼夜循环
- **创世护身符**：传奇任务奖励，被动回复 + 强力增益
- **法杖**：召唤、否定、创造、音叉
- **高级制作**：文化特定的配方和材料

### 🚀 开发状态

**当前版本**: `0.1.4-alpha` (Forge 1.20.1)
**最后更新**: 2026 年 1 月 17 日

#### ✅ 已完成系统

| 系统 | 数量 | 状态 | 描述 |
|------|------|------|------|
| **物品系统** | 225+ 物品 | ✅ 完成 | 所有文化物品、工具、食物、材料已注册 |
| **建筑材料** | 30 物品 | ✅ 完成 | 文化特色建筑方块和材料 |
| **任务系统** | 8 物品 | ✅ 完成 | 创世任务物品及特殊创世护身符 |
| **贸易品** | 14 物品 | ✅ 完成 | 染料、香料、贸易奢侈品 |
| **战利品系统** | 8 物品 | ✅ 完成 | 孤立建筑探索奖励 |
| **货币系统** | 3 层 | ✅ 完成 | 铜、银、金第纳尔 |
| **食物系统** | 45+ 食物 | ✅ 完成 | 文化特色料理及效果 |
| **魔法物品** | 10+ 物品 | ✅ 完成 | 护符、法杖及特殊能力 |
| **方块系统** | 35 方块 | ✅ 完成 | 建筑方块、矿石、功能方块 |
| **翻译系统** | 450+ 条 | ✅ 完成 | 英文/中文全内容翻译 |
| **创造标签** | 9 个 | ✅ 完成 | 所有文化 + 实用标签 |

#### 🔄 开发中

| 系统 | 进度 | 备注 |
|------|------|------|
| **村庄核心系统** | 30% | 核心村庄逻辑 |
| **建筑框架** | 15% | 建造系统 |
| **实体系统** | 10% | NPC 村民 |
| **战利品表** | 0% | 与战利品物品集成 |

#### 📋 计划功能

- **千年箱系统**: 与村庄集成的安全储存
- **道路系统**: 文化建筑连接
- **任务故事线**: 完整创世任务实现
- **AI 村民**: 具有复杂行为的智能 NPC
- **建筑生成器**: 程序化建筑系统
- **世界生成**: 加里亚奈特矿石生成、孤立建筑

### 🎯 技术成就

#### 🏗️ 现代架构

- **包结构**: 清洁的 `com.jasoncian.millenaire_rewrite` 组织（包名与 mod ID `millenaire_rewrite` 沿用上游，以保持存档兼容）
- **类型安全**: 变体方块和物品的完整泛型系统
- **数据生成**: 自动化资源文件生成（200+文件）
- **本地化**: 完整的英中文翻译支持
- **创造标签**: 所有文化已实现并分类

#### 📊 项目统计

- **Java 类**: 140+ 个现代实现
- **材质资产**: 950+ 个高质量 PNG 文件
- **生成资源**: 180+ 个自动创建的文件
- **注册物品**: 225+ 个独特物品，完整集成
- **注册方块**: 35 个方块，支持完整变体
- **翻译键**: 450+ 条双语条目

#### 🔧 代码质量

- **现代 Java 17**: 最新语言特性和最佳实践
- **Forge 47.4.3**: 当前稳定 API 实现
- **清洁架构**: 模块化设计，易于扩展
- **全面文档**: 完整 JavaDoc 覆盖

### 🛠️ 安装与开发

#### 前置条件

- **Minecraft**: 1.20.1
- **Forge**: 47.4.3+
- **Java 开发工具包**: 17+
- **IDE**: IntelliJ IDEA 或 Eclipse，配合 Minecraft 开发工具包

#### 快速开始

```bash
# 克隆仓库
git clone https://github.com/TzuHwang/dsh-refined.git
cd dsh-refined

# 设置开发环境
./gradlew genEclipseRuns  # Eclipse用
./gradlew genIntellijRuns # IntelliJ用

# 开发运行
./gradlew runClient
```

#### 构建

```bash
# 构建模组JAR
./gradlew build

# 生成数据文件
./gradlew runData
```

### 🤝 贡献

我们欢迎贡献！以下是当前的开发优先级：

#### 当前优先级

1. **村庄管理系统** - 核心村庄逻辑和数据结构
2. **建筑生成** - 自动化建造系统
3. **NPC AI 系统** - 智能村民行为
4. **战利品表集成** - 将战利品物品连接到世界生成

### 📜 许可证

本项目采用 MIT 许可证 - 详见[LICENSE.txt](LICENSE.txt)。

### 🙏 致谢

- **原版千年村庄团队** - 创造了备受喜爱的原版模组
- **[JasonCian](https://github.com/JasonCian/Millenaire-rewrite)** - 本项目所基于的 Millenaire Rewrite 1.20.1 移植
- **MinecraftForge 团队** - 提供模组开发框架
- **社区贡献者** - 提供反馈和建议

---

## 🔗 Links | 链接

- **Issues** | **问题报告**: [GitHub Issue](https://github.com/TzuHwang/dsh-refined/issues)
- **Upstream** | **上游项目**: [JasonCian/Millenaire-rewrite](https://github.com/JasonCian/Millenaire-rewrite)
- **Original Mod** | **原版模组**: [Millenaire Legacy](https://millenaire.org)

---

_Last updated: September 5, 2025 | 最后更新：2025 年 9 月 5 日_
