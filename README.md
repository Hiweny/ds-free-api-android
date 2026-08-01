# DS Free API · Android

将 [ds-free-api](https://github.com/NIyueeE/ds-free-api) 打包为 Android APK，开箱即用的 DeepSeek 反代理服务。

**一键安装，自动启动，无需 Root、无需 Termux、无需命令行。**

---

## 功能特性

- **从源码交叉编译**：CI 自动从 ds-free-api 源码交叉编译 aarch64-linux-android 二进制，无需手动下载
- **安装即用**：APK 安装后打开 App，自动在后台启动 DeepSeek API 代理
- **内置 Web 管理面板**：账号管理、API Key 创建、模型配置、日志查看，全部在 App 内完成
- **健康检查机制**：App 启动后轮询健康接口，服务就绪后自动加载管理面板，支持超时重试
- **深色模式**：跟随系统深色模式，WebView 自适应暗色主题
- **前台通知驻留**：持久通知栏，随时暂停/恢复/停止服务
- **开机自启**：手机重启后自动拉起代理服务
- **崩溃自动重启**：二进制异常退出后自动重启，最多重试 5 次
- **安全隔离**：所有流量仅限本地回环（127.0.0.1），不暴露到公网

## 快速开始

### 安装

1. 从 [Releases](https://github.com/Hiweny/ds-free-api-android/releases) 下载最新 APK
2. 安装后打开「DS Free API」应用
3. 等待服务启动（加载页显示进度，就绪后自动进入管理面板）
4. 设置管理员密码，在管理面板中添加 DeepSeek 账号、创建 API Key

### 使用

| 客户端 | 配置 |
|--------|------|
| **API 地址** | `http://127.0.0.1:22217/v1` |
| **API Key** | 在管理面板中创建 |
| **管理面板** | `http://127.0.0.1:22217/admin` |

支持的客户端：
- ChatGPT 客户端（设置自定义 API 端点）
- OpenCat / ChatBox / Chatwise
- 任何支持 OpenAI 兼容 API 的工具

## 技术架构

```
┌─────────────────────────────────┐
│         DS Free API APK         │
│                                 │
│  ┌───────────────────────────┐  │
│  │     MainActivity          │  │
│  │  (WebView 加载管理面板)    │  │
│  │  + 健康检查 + 深色模式     │  │
│  └───────────┬───────────────┘  │
│              │                   │
│  ┌───────────▼───────────────┐  │
│  │     ProxyService          │  │
│  │  (Foreground Service)     │  │
│  │  + 自动重启 + 进程监控     │  │
│  │                           │  │
│  │  ┌─────────────────────┐  │  │
│  │  │  ds-free-api 二进制  │  │  │
│  │  │  (Rust, ARM64)      │  │  │
│  │  └──────────┬──────────┘  │  │
│  │             │              │  │
│  │  127.0.0.1:22217          │  │
│  └─────────────┼──────────────┘  │
└────────────────┼──────────────────┘
                 │
          ┌──────▼──────┐
          │  客户端 App  │
          │  (ChatBox,  │
          │   OpenCat)  │
          └─────────────┘
```

## 自行构建

### 前置要求

- JDK 17
- Android SDK (API 35) + NDK
- Rust stable + cargo-ndk
- Bun (用于构建前端)

### 构建步骤

```bash
# 1. 克隆 ds-free-api 源码
git clone https://github.com/NIyueeE/ds-free-api.git ds-free-api-src

# 2. 构建前端
cd ds-free-api-src/web
bun install && bun run build
cd ../..

# 3. 交叉编译二进制 (需要 Android NDK)
cd ds-free-api-src
cargo ndk -t arm64-v8a -p 24 build --release
cd ..

# 4. 复制二进制到 assets
cp ds-free-api-src/target/aarch64-linux-android/release/ds-free-api app/src/main/assets/

# 5. 构建 APK
gradle assembleRelease

# 6. APK 位于
# app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions

推送代码到 main 分支即自动构建（从源码交叉编译 + 打包 APK），或手动触发 `workflow_dispatch`。

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | WebView 访问本地管理面板 |
| FOREGROUND_SERVICE | 持久通知栏，保持服务存活 |
| FOREGROUND_SERVICE_DATA_SYNC | 数据同步类型前台服务 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| WAKE_LOCK | 保持服务运行 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 防止被系统杀后台 |

## 依赖项目

- [ds-free-api](https://github.com/NIyueeE/ds-free-api) - Rust 编写的 DeepSeek 反代理服务（GPL-3.0）

## 许可证

本项目继承 ds-free-api 的 GPL-3.0 许可证。

---

**免责声明**：本项目仅供学习交流，请遵守 DeepSeek 服务条款。使用本项目产生的任何后果由使用者自行承担。
