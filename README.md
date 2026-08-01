# DS Free API · Android

将 [ds-free-api](https://github.com/NIyueeE/ds-free-api) 打包为 Android APK，开箱即用的 DeepSeek 反代理服务。

**一键安装，自动启动，无需 Root、无需 Termux、无需命令行。**

---

## 功能特性

- **安装即用**：APK 安装后打开 App，自动在后台启动 DeepSeek API 代理
- **内置 Web 管理面板**：账号管理、API Key 创建、模型配置、日志查看，全部在 App 内完成
- **前台通知驻留**：持久通知栏，随时暂停/恢复/停止服务
- **开机自启**：手机重启后自动拉起代理服务
- **零成本**：使用 DeepSeek 网页版免费账号，无需官方 API Key
- **安全隔离**：所有流量仅限本地回环（127.0.0.1），不暴露到公网

## 快速开始

### 安装

1. 从 [Releases](https://github.com/Hiweny/ds-free-api-android/releases) 下载最新 APK
2. 安装后打开「DS Free API」应用
3. 等待服务启动（通知栏显示「DeepSeek API 代理已运行」）
4. App 自动加载管理面板，设置管理员密码
5. 在管理面板中添加 DeepSeek 账号、创建 API Key

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
│  └───────────┬───────────────┘  │
│              │                   │
│  ┌───────────▼───────────────┐  │
│  │     ProxyService          │  │
│  │  (Foreground Service)     │  │
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
- Android SDK (API 35)

### 构建步骤

```bash
# 1. 下载 ds-free-api 二进制
curl -L "https://github.com/NIyueeE/ds-free-api/releases/latest/download/ds-free-api-*-aarch64-linux-gnu.tar.gz" -o ds.tar.gz
tar -xzf ds.tar.gz
cp ds-free-api app/src/main/assets/

# 2. 构建 APK
gradle assembleRelease

# 3. APK 位于
# app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions

推送代码到 main 分支即自动构建，或手动触发 `workflow_dispatch`。

## 权限说明

| 权限 | 用途 |
|------|------|
| FOREGROUND_SERVICE | 持久通知栏，保持服务存活 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 防止被系统杀后台 |

**不需要存储权限、不需要网络权限（二进制自己管理 HTTP 本地监听）**

## 依赖项目

- [ds-free-api](https://github.com/NIyueeE/ds-free-api) - Rust 编写的 DeepSeek 反代理服务（GPL-3.0）

## 许可证

本项目继承 ds-free-api 的 GPL-3.0 许可证。

---

**免责声明**：本项目仅供学习交流，请遵守 DeepSeek 服务条款。使用本项目产生的任何后果由使用者自行承担。