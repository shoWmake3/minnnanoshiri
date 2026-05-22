package com.jienoshiri.platform.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 帖子文档类 (用于Elasticsearch索引)
 * indexName: 索引名称
 */
@Data
@Document(indexName = "post_index")
public class PostDocument {

    @Id
    private Long id;

    // 使用 ik_max_word 分词器 (前提: ES安装了ik插件)
    // 如果没有安装ik插件，请使用 analyzer = "standard"
    @Field(type = FieldType.Text, analyzer = "standard", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "standard", searchAnalyzer = "ik_smart")
    private String content;

    @Field(type = FieldType.Integer)
    private Integer status; // 0:待审核, 1:正常

    @Field(type = FieldType.Date)
    private java.time.LocalDateTime createTime;
}