<div align="center">

# LevelLeap · CET-6

**把六级真题，变成每天看得见的进步。**

离线优先的六级学习 Android App，围绕词汇、阅读、翻译与写作安排学习和复习。

![Android](https://img.shields.io/badge/Android-26%2B-3F7D53?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Study](https://img.shields.io/badge/Study-Offline%20first-7C8F78)

</div>

---

## 学习功能

| 模块 | 可以做什么 |
| --- | --- |
| **词汇** | 浏览和搜索六级词汇、查看词义与例句；设置每日新词目标；通过记忆反馈安排后续复习；创建收藏夹。 |
| **阅读** | 练习仔细阅读、长篇匹配和选词填空；逐句查看译文与结构分析，也可整篇阅读或查看题目答案。 |
| **翻译** | 先自己翻译，再查看参考译文和优化译文；练习文本支持查词和记笔记。 |
| **作文** | 查看题目及范文；长按选取好词好句，可保存笔记或标黄，并整理个人写作笔记。 |
| **复习与笔记** | 单词和笔记进入复习队列；复习到期按手机本地日期判断，跨过午夜后更新当天队列和学习进度。 |

在阅读、翻译和作文内容中，可以选中文字来查词、记笔记或标记重点。学习状态、收藏、笔记和高亮都保存在本机。

## App 实际运行截图

以下为 App 的实际运行截图。

<div align="center">
  <img src="docs/screenshots/home.png" alt="首页：今日学习进度和学习模块" width="290">
  <img src="docs/screenshots/reading.png" alt="阅读练习列表" width="290">
</div>

<div align="center">
  <img src="docs/screenshots/reading-detail.png" alt="阅读逐句精读：原文、译文和结构分析" width="290">
  <img src="docs/screenshots/translation.png" alt="翻译训练：参考译文和优化译文" width="290">
</div>

## 运行项目

1. 安装 Android Studio 和 JDK 17。
2. 克隆仓库，在 Android Studio 中打开项目根目录。
3. 等待 Gradle 同步完成。
4. 连接 Android 手机或启动模拟器，运行 `app`。

应用包名：`com.cet6.study`。若要覆盖安装到已有 App，请使用相同包名和原有签名。

## 替换自己的内容数据库

本仓库不包含个人更新版内容库。如果要使用自己的 `CET6_LevelLeap_content_20261013_v39(1).db`：

1. 将文件复制到 `app/src/main/assets/`。
2. 重命名为 `cet6_content.db`，覆盖同名文件。
3. 保留 `app/src/main/assets/content_version.txt`，不要删除。
4. 在 Android Studio 重新运行，或构建并安装更新后的 App。

**学习记录保留说明：**内容库与用户学习数据分开保存。使用相同包名和签名覆盖安装时，已有的单词状态、复习记录、收藏、笔记和标黄内容会保留。不要清除 App 数据或先卸载旧版。

## 技术与目录

- **Kotlin + Jetpack Compose**：Android 界面与交互
- **Room + SQLite**：本地学习数据和离线内容
- `app/src/main/java/com/cet6/study/`：界面、学习流程与数据层
- `app/src/main/assets/`：内容数据库和版本标识
- `app/src/test/`：复习日期逻辑测试

复习日期逻辑包含 5 项单元测试。完整构建与手机运行请在 Android Studio 中完成。
