# 贡献指南

感谢你有兴趣改进 JMH！本文说明如何参与开发。

## 开始之前

- 提交前请先搜索是否已有相同 Issue / PR
- **安全漏洞请勿公开提交**，请按 [SECURITY.md](SECURITY.md) 私下报告
- 较大的功能改动建议先开 Issue 讨论，避免白做工

## 开发环境

| 要求 | 版本 |
|------|------|
| JDK | 17+ |
| Android SDK | Platform 34、Build-Tools 34.0.0 |
| Gradle | 使用仓库自带的 `gradlew`，无需单独安装 |

克隆后：

```bash
cp local.properties.example local.properties   # 填入你的 SDK 路径
./gradlew assembleDebug
```

## 代码规范

- Kotlin 官方代码风格（4 空格缩进）
- 注释使用中文或英文均可，但同一文件内保持一致
- 新增功能请同步补充对应测试
- **加密相关代码改动必须附带测试**，并确保全部测试通过

### 关键约束

1. **不要引入网络权限**。本项目的核心卖点之一是纯离线，任何形式的联网需求都不接受。
2. **不要降低加密强度**。禁止减少 PBKDF2 迭代次数、改用弱算法或缩短密钥长度。
3. **不要实现密码找回**。任何形式的后门或主密码都会破坏威胁模型。
4. **加密逻辑与 UI 保持解耦**，`crypto/` 包不应依赖任何 Android UI 组件。

## 提交信息

建议使用 [约定式提交](https://www.conventionalcommits.org/zh-hans/)：

```
feat: 支持文件夹批量加密
fix: 修复大文件解密时进度回调不更新
refactor: 拆分 VaultDir 的 SAF 实现
test: 补充篡改文件头的测试用例
docs: 更新构建说明
```

## 提交 PR 前

```bash
./gradlew assembleDebug                 # 确保能编译
./gradlew connectedDebugAndroidTest     # 确保测试通过（需设备/模拟器）
```

PR 描述中请说明：

- 改了什么、为什么改
- 如何验证（截图 / 测试结果更佳）
- 是否影响 `.jmh` 文件格式兼容性

## 关于文件格式兼容性

若改动涉及 `.jmh` 格式，**必须**：

1. 递增格式版本号（`JmhFormat.VERSION`）
2. 在读取逻辑中兼容旧版本，或在文档中明确说明不兼容
3. 在 CHANGELOG 中标注

## 许可证

贡献的代码将按本项目的 [MIT 许可证](LICENSE) 发布，提交 PR 即表示你同意此条款。
