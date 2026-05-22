# Elasticsearch 8.13.4 搜索问题排查指南

## 问题原因分析

您的 Elasticsearch 搜索功能搜不到东西，可能有以下几个原因：

### 1. **分词器问题（最常见）** ⭐
您的代码使用了 `ik_max_word` 分词器：
```java
@Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
```

**问题**：Elasticsearch 8.x 默认不包含 IK 分词器插件。如果 ES 没有安装 IK 插件，索引创建会失败或使用默认分词器，导致搜索不匹配。

**解决方案**（已修改）：
- 将分词器改为 `standard`（标准分词器，ES 内置）
- 或者安装 IK 插件到 ES

### 2. **索引未创建或映射错误**
- 检查索引 `post_index` 和 `wiki_index` 是否存在
- 检查字段映射是否正确

### 3. **数据未同步到 ES**
- 发布帖子时，`syncPostToES` 方法是否成功执行
- 检查日志中是否有 "ES 索引更新成功" 的消息

### 4. **状态过滤问题**
- 搜索时只查询 `status=1` 的帖子
- 确保您的帖子状态是 1（已审核）

### 5. **ES 刷新延迟**
- ES 默认有 1 秒的刷新间隔，刚写入的数据可能立即可搜不到
- 已添加等待逻辑

---

## 已做的修改

### 1. 修改分词器为标准分词器
**文件**: `PostDocument.java` 和 `WikiDoc.java`
```java
// 之前
@Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")

// 之后
@Field(type = FieldType.Text, analyzer = "standard")
```

### 2. 添加模糊搜索（Fuzziness）
**文件**: `PostService.java - getPostIdsByES()`
```java
.multiMatch(mm -> mm
    .query(keyword)
    .fields("title", "content")
    .fuzziness("AUTO") // 新增：允许模糊匹配
)
```

### 3. 添加降级机制
**文件**: `PostService.java - getPostList()`
- 如果 ES 搜索不到结果，自动降级到 MySQL LIKE 搜索
- 添加详细日志输出

### 4. 添加调试日志
- 搜索关键词日志
- ES 命中数日志
- 降级搜索日志

---

## 手动排查步骤

### 步骤 1: 检查 ES 是否正常运行
```bash
curl http://localhost:9200/_cluster/health?pretty
```

### 步骤 2: 检查索引是否存在
```bash
curl http://localhost:9200/_cat/indices?v
```

应该看到 `post_index` 和 `wiki_index`

### 步骤 3: 查看索引映射
```bash
curl http://localhost:9200/post_index/_mapping?pretty
```

检查 `title` 和 `content` 字段的 analyzer 是什么

### 步骤 4: 测试分词效果
```bash
# 测试 standard 分词器
curl -X POST "http://localhost:9200/_analyze?pretty" -H 'Content-Type: application/json' -d'
{
  "analyzer": "standard",
  "text": "你好世界"
}'
```

### 步骤 5: 查看索引中的数据
```bash
curl "http://localhost:9200/post_index/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {"match_all": {}}
}'
```

### 步骤 6: 测试搜索
```bash
curl "http://localhost:9200/post_index/_search?pretty" -H 'Content-Type: application/json' -d'
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "你的关键词",
            "fields": ["title", "content"]
          }
        }
      ],
      "filter": [
        {"term": {"status": 1}}
      ]
    }
  }
}'
```

---

## 如果需要中文分词

如果标准分词器对中文支持不够好，您可以：

### 方案 A: 安装 IK 分词器插件
```bash
# 进入 ES 插件目录
cd /path/to/elasticsearch/plugins

# 下载并安装 IK 插件（版本要与 ES 一致）
bin/elasticsearch-plugin install https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v8.13.4/elasticsearch-analysis-ik-8.13.4.zip

# 重启 ES
```

然后改回代码中的分词器配置。

### 方案 B: 使用自定义同义词过滤器
在 `application.yml` 中添加分析器配置。

---

## 验证修复

1. **重启应用**
2. **发布一个新帖子**，查看日志：
   - `>>> ES 索引更新成功`
   - `>>> ES 验证搜索命中数`
3. **搜索该帖子**，查看日志：
   - `>>> 接收到搜索请求，关键词`
   - `>>> ES 搜索返回帖子数`
4. **如果还是搜不到**，会自动降级到 MySQL，查看：
   - `>>> ES 未找到结果，降级到 MySQL 搜索...`
   - `>>> MySQL 降级搜索返回帖子数`

---

## 常见错误及解决

| 错误现象 | 可能原因 | 解决方法 |
|---------|---------|---------|
| 搜索返回空结果 | 分词器不匹配 | 使用 standard 或安装 IK |
| ES 连接失败 | ES 未启动 | 启动 ES 服务 |
| 索引不存在 | 第一次运行 | 自动创建或手动创建 |
| 状态过滤太严格 | 帖子 status≠1 | 修改帖子状态为 1 |
| 大小写问题 | 关键词大小写 | fuzziness 已处理 |

