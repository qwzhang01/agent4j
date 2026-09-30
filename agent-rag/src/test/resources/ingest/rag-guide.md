---
title: 检索增强指南
version: 2.1
product: "agent4j"
---

# Agent4j RAG 指南

本文介绍 agent4j 的检索增强生成（RAG）模块。It covers **ingestion** and retrieval.

## 安装 Installation

在 `pom.xml` 中加入依赖：

```xml
<dependency>
  <artifactId>agent-rag</artifactId>
</dependency>
```

### 配置项

| 参数 | 默认值 | 说明 |
|------|-------:|------|
| targetChars | 800 | 目标块长度 |
| maxChars | 1500 | 最大块长度 |

- 支持 Markdown
- 支持 PDF
  - 基于 PDFBox

> 注意：表格和代码块不会被切开。
> Tables and code are atomic.

## 检索 Retrieval

混合检索结合 BM25 与向量召回。

    indented code line
      second line
