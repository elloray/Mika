# 开发文档

简体中文 | [English](README.en.md)

写给想读代码、改代码、补知识的人。怎么构建、怎么提 PR，先看[贡献指南](../../.github/CONTRIBUTING.zh-CN.md)。用户文档在[上一级](../README.md)。

## 先读

| 文档 | 讲什么 |
| --- | --- |
| [architecture.md](architecture.md) | 按代码现状写的整体结构，带类名和文件路径：主循环、工具、手机操作、浏览器、沙箱、电脑连接、知识与技能 |
| [roadmap.md](roadmap.md) | 已完成、接下来、已知问题与暂缓 |

## 写知识和技能

| 文档 | 讲什么 |
| --- | --- |
| [knowledge-format.md](knowledge-format.md)（[English](knowledge-format.en.md)） | 内置 App 知识（`APP.md`）、网站知识（`SITE.md`）和技能（`SKILL.md`）的格式 |
| [skill-scripts.md](skill-scripts.md) | 知识和技能自带的脚本怎么放、在沙箱里怎么运行 |

## 专题说明与验证记录

| 文档 | 讲什么 |
| --- | --- |
| [app-access.md](app-access.md) | 应用访问规则、主动分享授权与标准手机工具的限制边界 |
| [foldables.md](foldables.md) | 折叠屏布局规则、铰链避让、状态连续性与设备验收 |
| [account-signin.md](account-signin.md) | 用账号登录模型服务商（不填 Key）的设计，以及明确不做的部分 |
| [chatgpt-signin-check.md](chatgpt-signin-check.md) | 上手机之前，在电脑上用 App 同一套代码走一遍「用 ChatGPT 登录」 |
| [remote-device-access-check.md](remote-device-access-check.md) | 远程亮屏与解锁的验证记录 |
| [remote-mcp.md](remote-mcp.md) | 自托管远程 CLI / MCP 的部署、手机鉴权和断线语义 |

## 仓库里别处的开发文档

- [`eval/README.md`](../../eval/README.md)：真机评测。
- [`tools/sandbox/README.md`](../../tools/sandbox/README.md)：沙箱原生程序和自带运行环境的重新构建。

开发文档目前除知识格式外只有中文版。
