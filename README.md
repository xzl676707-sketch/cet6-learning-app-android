<div align="center">

# LevelLeap · CET-6

**把六级真题，变成每天看得见的进步。**

离线优先的六级学习 Android App，包含词汇、阅读、翻译、写作和复习。

![Android](https://img.shields.io/badge/Android-26%2B-3F7D53?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Study](https://img.shields.io/badge/Study-Offline%20first-7C8F78)

</div>

---

## 学习功能

| 模块 | 可以做什么 |
| --- | --- |
| **词汇** | 浏览、搜索六级词汇并查看词义和例句；设置每日新词目标；用记忆反馈安排复习；创建收藏夹。 |
| **阅读** | 练习仔细阅读、长篇匹配和选词填空；逐句查看译文与结构分析；整篇阅读或查看题目答案。 |
| **翻译** | 先自己翻译，再查看参考译文和优化译文；可查词、记笔记。 |
| **作文** | 查看题目和范文；选取好词好句、标黄重点并保存写作笔记。 |
| **复习与笔记** | 单词和笔记加入复习队列；到期日按手机本地日期计算，跨过午夜后切换当天队列和进度。 |

阅读、翻译和作文中的文本支持选择后查词、记笔记或标记重点。学习状态、收藏、笔记和高亮保存在本机。

## 界面与功能说明

### 1. 首页：查看今天的学习安排

<p align="center"><img src="docs/screenshots/home.png" alt="LevelLeap 首页" width="340"></p>

首页集中显示待复习数量、已学词数和可学习新词数。新词进度按每日目标计算，复习进度按当天实际到期的单词和笔记计算。点击「开始复习」或「学习新词」即可进入对应流程；下方可以打开阅读、翻译、作文和全部笔记。

### 2. 阅读列表：选择试卷和练习方式

<p align="center"><img src="docs/screenshots/reading.png" alt="阅读材料与练习方式" width="340"></p>

先选择试卷，再选择仔细阅读、长篇匹配或选词填空。每篇文章可以进入「开始精读」逐句学习，也可以选择「整篇阅读」连续阅读；需要核对时，可打开本套阅读题目与答案。

### 3. 逐句精读：一句一句看原文、译文和分析

<p align="center"><img src="docs/screenshots/reading-detail.png" alt="阅读逐句精读页面" width="340"></p>

逐句精读时，先读当前英文句子，再点句子或卡片空白处显示对应中文译文和结构分析；看懂后继续到下一句。这样可以按句子顺序学习，不必一开始就面对整篇译文。长按选中文字还可以记录笔记或查单词。

### 4. 翻译训练：先自己翻，再看参考表达

<p align="center"><img src="docs/screenshots/translation.png" alt="翻译训练页面" width="340"></p>

先阅读中文题目并尝试自己翻译，点击卡片后再查看参考译文和优化译文。练习文本支持选词、复制、查单词和记笔记，方便积累表达。

## 运行项目

1. 安装 Android Studio 和 JDK 17。
2. 克隆仓库，在 Android Studio 中打开项目根目录。
3. 等待 Gradle 同步完成。
4. 连接 Android 手机或启动模拟器，运行 `app`。

应用包名：`com.cet6.study`。覆盖安装到已有 App 时，请使用相同包名和原有签名。

## 内容库与手机更新

`main` 已包含补充完成的 `app/src/main/assets/cet6_content.db`（约 5.41 MB，2500 个单词）；从仓库拉取后不需要再手动替换。内容库更新时，应用会验证完整性和原有条目的编号，然后替换手机里的离线内容库。

单词学习状态、复习队列、收藏、笔记、标黄和阅读进度保存在独立的 `user_data.db` 中。使用原来的签名和包名 `com.cet6.study` 覆盖安装即可保留；不要卸载旧 App 或清除数据。具体步骤见 [更新到手机](%E6%9B%B4%E6%96%B0%E5%88%B0%E6%89%8B%E6%9C%BA.md)。

## 技术与目录

- **Kotlin + Jetpack Compose**：Android 界面与交互
- **Room + SQLite**：本地学习数据和离线内容
- `app/src/main/java/com/cet6/study/`：界面、学习流程与数据层
- `app/src/main/assets/`：内容数据库和版本标识
- `app/src/test/`：复习日期逻辑测试

复习日期逻辑包含 5 项单元测试。四张功能截图均为 App 实际运行画面。
