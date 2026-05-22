package com.jienoshiri.platform.repository;
import com.jienoshiri.platform.document.WikiDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WikiEsRepository extends ElasticsearchRepository<WikiDoc, Long> {
    // ⭐ 新增：带状态过滤的全文搜索方法
    List<WikiDoc> findByTitleOrContentAndStatus(String title, String content, Integer status);
}