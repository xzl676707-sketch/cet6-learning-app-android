# CET-6 六级学习 App

离线优先的 CET-6 学习 App 原型，使用 Kotlin、Jetpack Compose、Room 和 SQLite。

## 当前已接入
- 首页学习概览和模块入口
- 2500 词浏览、搜索、词义、默认例句与三档记忆反馈
- 阅读逐句译文/结构分析，以及阅读题目答案
- 翻译题目与参考/优化译文
- 作文题目、参考/优秀范文和个人笔记
- 独立 `user_data.db` 保存背词状态和笔记；内容库为只读 SQLite 资源
- 例句不满三条的词会标记为补充中，不进入今日新词队列

## 打开
1. 使用 Android Studio 打开本目录。
2. 使用 JDK 17，等待 Gradle 同步。
3. 选择 Android 模拟器或手机，运行 `app`。

当前执行环境没有 Android SDK/Gradle，因此这里未生成 APK；代码项目可在 Android Studio 中同步、构建。内容库版本在 `app/src/main/assets/content_version.txt`，更新内容库时替换 `cet6_content.db` 并递增版本字符串。用户学习状态保存在独立数据库中。
