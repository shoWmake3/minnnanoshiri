package com.jienoshiri.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode; // ⭐ 补充引用
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jienoshiri.platform.dto.CommentVo;
import com.jienoshiri.platform.dto.PostVo;
import com.jienoshiri.platform.entity.Comment;
import com.jienoshiri.platform.entity.Post;
import com.jienoshiri.platform.entity.PostLike;
import com.jienoshiri.platform.entity.SysUser;
import com.jienoshiri.platform.mapper.CommentMapper;
import com.jienoshiri.platform.mapper.PostLikeMapper;
import com.jienoshiri.platform.mapper.PostMapper;
import com.jienoshiri.platform.mapper.UserMapper;
import com.jienoshiri.platform.utils.LocationUtils;
import com.jienoshiri.platform.utils.SensitiveWordUtil;
import com.jienoshiri.platform.document.PostDocument;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PostService {
    // 工具对象初始化
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PostLikeMapper postLikeMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private SensitiveWordUtil sensitiveWordUtil;

    @Autowired
    private com.jienoshiri.platform.mapper.SysConfigMapper sysConfigMapper; // 注入Mapper

    @Autowired
    private ElasticsearchOperations elasticsearchOperations; // 新增注入

    private Map<String, Double> weightCache = new java.util.HashMap<>();

    @jakarta.annotation.PostConstruct
    public void initWeights() {
        refreshWeights();
    }

    /**
     * 发布帖子
     */
    public void publishPost(Post post) {
        // 1. 过滤标题
        if (sensitiveWordUtil.isContaintSensitiveWord(post.getTitle(), 1)) {
            String safeTitle = sensitiveWordUtil.replaceSensitiveWord(post.getTitle(), 1, "*");
            post.setTitle(safeTitle);
        }

        // 2. 过滤正文
        if (sensitiveWordUtil.isContaintSensitiveWord(post.getContent(), 1)) {
            String safeContent = sensitiveWordUtil.replaceSensitiveWord(post.getContent(), 1, "*");
            post.setContent(safeContent);
        }

        // ⭐ 3. 自动补充经纬度 (OpenStreetMap)
        // 如果用户填了地点名，但没给坐标（比如手写的），就自动去查坐标
        if (post.getLocationName() != null && !post.getLocationName().isEmpty()
                && post.getLatitude() == null) {
            fillGeoInfo(post);
        }

        post.setCreateTime(LocalDateTime.now());
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setStatus(0); // 默认待审核
        postMapper.insert(post);

        // 3. 【新增】同步到 Elasticsearch
        syncPostToES(post);

        // 发帖奖励声望
        changeReputation(post.getUserId(), 5, "发布帖子");
    }

    /**
     * 辅助方法：同步帖子到 ES
     */
    private void syncPostToES(Post post) {
        try {
            PostDocument doc = new PostDocument();
            doc.setId(post.getId());
            doc.setTitle(post.getTitle());
            doc.setContent(post.getContent());
            doc.setStatus(post.getStatus());
            doc.setCreateTime(post.getCreateTime());

            // 使用 elasticsearchOperations 的 save 方法
            elasticsearchOperations.save(doc);
            System.out.println(">>> ES 索引更新成功: ID=" + post.getId());
        } catch (Exception e) {
            System.err.println(">>> ES 索引更新失败: " + e.getMessage());
        }
    }

    /**
     * 使用 OpenStreetMap 获取经纬度 (免费/无Key)
     */
    private void fillGeoInfo(Post post) {
        try {
            System.out.println(">>> [OSM] 正在解析地址: " + post.getLocationName());

            // 构造请求 URL (limit=1 只取最匹配的一个)
            String url = "https://nominatim.openstreetmap.org/search?q=" + post.getLocationName() + "&format=json&limit=1";

            // 设置 User-Agent (OSM 要求必须带，否则报 403)
            HttpHeaders headers = new HttpHeaders();
            headers.add("User-Agent", "Jienoshiri-Student-Project/1.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.isArray() && root.size() > 0) {
                    JsonNode first = root.get(0);
                    String lat = first.get("lat").asText();
                    String lon = first.get("lon").asText();

                    post.setLatitude(new BigDecimal(lat));
                    post.setLongitude(new BigDecimal(lon));
                    System.out.println(">>> [OSM] 解析成功: " + lat + ", " + lon);
                } else {
                    System.err.println(">>> [OSM] 未找到该地点，将不保存坐标");
                }
            }
        } catch (Exception e) {
            System.err.println(">>> [OSM] 网络或解析异常: " + e.getMessage());
        }
    }

    /**
     * 获取帖子列表 (首页信息流)
     */
    public List<PostVo> getPostList(BigDecimal userLat, BigDecimal userLng, String keyword, Long currentUserId, String identityType) {
        List<Post> posts;
        if (keyword != null && !keyword.trim().isEmpty()) {
            posts = getPostIdsByES(keyword);
        } else {
            // 1. 查所有帖子
            QueryWrapper<Post> query = new QueryWrapper<>();
            // 查状态为 1(正常) 与 3(已转Wiki) 的帖子，配合 weight_wiki 的加权逻辑
            query.in("status", 1, 3);
            if (keyword != null && !keyword.trim().isEmpty()) {
                query.and(w -> w.like("title", keyword).or().like("content", keyword));
            }
            query.orderByDesc("create_time");
            posts = postMapper.selectList(query);
        }
            // 2. 准备推荐数据
            List<Long> userCfIds = new ArrayList<>();
            List<Long> contentBasedIds = new ArrayList<>();
            if (currentUserId != null) {
                try {
                    userCfIds = recommendationService.recommendPostIds(currentUserId, 10);
                } catch (Exception e) {
                }

                try {
                    contentBasedIds = recommendationService.recommendByContent(identityType);
                } catch (Exception e) {
                }
            }
            List<PostVo> result = new ArrayList<>();
            // 3. 混合打分
            for (Post post : posts) {
                PostVo vo = new PostVo();
                BeanUtils.copyProperties(post, vo);
                SysUser author = userMapper.selectById(post.getUserId());
                if (author != null) {
                    vo.setAuthorName(author.getNickname());
                    vo.setAuthorAvatar(author.getAvatar());
                    vo.setAuthorIdentity(author.getIdentityType());
                    vo.setAuthorReputation(author.getReputation());
                }
                // 计算 LBS 距离
                if (userLat != null && post.getLatitude() != null) {
                    double km = LocationUtils.getDistance(userLat, userLng, post.getLatitude(), post.getLongitude());
                    vo.setDistance(km);
                }
                // 填充点赞状态
                if (currentUserId != null) {
                    Long count = postLikeMapper.selectCount(new QueryWrapper<PostLike>()
                            .eq("user_id", currentUserId).eq("post_id", post.getId()));
                    vo.setIsLiked(count > 0);
                }
                // Redis 浏览量合并
                String viewKey = "post:view:" + post.getId();
                Integer redisViews = (Integer) redisTemplate.opsForValue().get(viewKey);
                if (redisViews != null) {
                    vo.setViewCount(vo.getViewCount() + redisViews);
                }
                //混合加权核心逻辑 (动态读取)
                double score = 0;
                // 权重 1: UserCF
                double wUserCF = getWeight("weight_user_cf", 1000.0);
                if (userCfIds.contains(post.getId())) {
                    score += wUserCF;
                    vo.setTitle("【猜你喜欢】" + vo.getTitle());
                }
                // 权重 2: Content-Based
                double wContent = getWeight("weight_content", 500.0);
                if (contentBasedIds.contains(post.getId())) {
                    score += wContent;
                    if (!vo.getTitle().startsWith("【猜你喜欢】")) {
                        vo.setTitle("【精选】" + vo.getTitle());
                    }
                }
                // 权重 3: 作者声望
                double wRep = getWeight("weight_reputation", 0.5);
                if (author != null && author.getReputation() != null) {
                    double repBonus = author.getReputation() * wRep;
                    score += Math.min(Math.max(repBonus, -500), 200);
                }
                // 权重 4: LBS
                double wLbs = getWeight("weight_lbs", 300.0);
                if (vo.getDistance() != null && vo.getDistance() < 10) {
                    score += wLbs;
                }
                // 权重 5: Wiki
                double wWiki = getWeight("weight_wiki", 200.0);
                if (post.getStatus() == 3) {
                    score += wWiki;
                }
                score += (double) post.getId() / 1000000.0;
                vo.setScore(score);
                result.add(vo);
            }
            // 4. 排序
            result.sort((o1, o2) -> Double.compare(o2.getScore(), o1.getScore()));
            return result;


    }

    /**
     * 使用 ES 搜索帖子 ID
     */
    private List<Post> getPostIdsByES(String keyword) {
        try {
            // 构建 ES 查询
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .must(m -> m.multiMatch(mm -> mm
                                    .query(keyword)
                                    .fields("title", "content")
                            ))
                            .filter(f -> f.term(t -> t.field("status").value(1))) // 只搜已审核
                    ))
                    .build();

            SearchHits<PostDocument> searchHits = elasticsearchOperations.search(query, PostDocument.class);

            // 提取 ID
            List<Long> postIds = searchHits.getSearchHits().stream()
                    .map(hit -> hit.getContent().getId())
                    .toList();

            if (postIds.isEmpty()) {
                return new ArrayList<>();
            }

            // 根据 ES 返回的 ID，去 MySQL 查完整数据 (为了兼容您后端的排序逻辑)
            return postMapper.selectBatchIds(postIds);

        } catch (Exception e) {
            System.err.println(">>> ES 搜索帖子失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    public boolean toggleLike(Long postId, Long userId) {
        QueryWrapper<PostLike> query = new QueryWrapper<>();
        query.eq("post_id", postId).eq("user_id", userId);
        PostLike exists = postLikeMapper.selectOne(query);

        Post post = postMapper.selectById(postId);
        if (post == null) return false;

        if (exists != null) {
            postLikeMapper.deleteById(exists.getId());
            post.setLikeCount(Math.max(0, post.getLikeCount() - 1));
            postMapper.updateById(post);
            return false;
        } else {
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setUserId(userId);
            like.setCreateTime(LocalDateTime.now());
            postLikeMapper.insert(like);

            SysUser liker = userMapper.selectById(userId);
            int bonus = (liker != null && liker.getReputation() >= 100) ? 3 : 1;
            changeReputation(post.getUserId(), bonus, "被高信誉用户点赞");

            post.setLikeCount(post.getLikeCount() + 1);
            postMapper.updateById(post);

            if (!post.getUserId().equals(userId)) {
                String likerName = (liker != null) ? liker.getNickname() : "有人";
                notificationService.send(
                        post.getUserId(),
                        "收到新点赞",
                        likerName + " 赞了你的帖子",
                        1,
                        postId
                );
            }
            return true;
        }
    }

    public void addComment(Comment comment) {
        // 过滤评论内容
        if (sensitiveWordUtil.isContaintSensitiveWord(comment.getContent(), 1)) {
            String safeContent = sensitiveWordUtil.replaceSensitiveWord(comment.getContent(), 1, "*");
            comment.setContent(safeContent);
        }

        SysUser user = userMapper.selectById(comment.getUserId());
        double currentReputation = (user.getReputation() == null) ? 0 : user.getReputation();
        double weight = 1.0 + (currentReputation / 100.0) * 0.5;
        weight = Math.min(weight, 3.0);
        comment.setWeightSnapshot(weight);

        if (comment.getScore() == null) {
            comment.setScore(0.0);
        }

        comment.setCreateTime(LocalDateTime.now());
        commentMapper.insert(comment);

        Post post = postMapper.selectById(comment.getPostId());
        if (post != null) {
            post.setCommentCount(post.getCommentCount() + 1);
            postMapper.updateById(post);

            if (comment.getScore() >= 4.0) {
                changeReputation(post.getUserId(), 3, "获得高分评价");
            }

            if (!post.getUserId().equals(comment.getUserId())) {
                String commenterName = (user != null) ? user.getNickname() : "有人";
                notificationService.send(
                        post.getUserId(),
                        "收到新评论",
                        commenterName + " 评论了你：" + comment.getContent(),
                        2,
                        post.getId()
                );
            }
        }
        changeReputation(comment.getUserId(), 2, "发布评论奖励");
    }

    public List<CommentVo> getComments(Long postId) {
        QueryWrapper<Comment> query = new QueryWrapper<>();
        query.eq("post_id", postId);
        query.orderByDesc("create_time");
        List<Comment> comments = commentMapper.selectList(query);
        List<CommentVo> result = new ArrayList<>();
        for (Comment c : comments) {
            CommentVo vo = new CommentVo();
            BeanUtils.copyProperties(c, vo);

            SysUser user = userMapper.selectById(c.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setIdentityType(user.getIdentityType());
                vo.setReputation(user.getReputation());
            }
            result.add(vo);
        }
        return result;
    }

    public void increaseViewCount(Long postId) {
        String key = "post:view:" + postId;
        redisTemplate.opsForValue().increment(key);
    }

    private void changeReputation(Long userId, int change, String reason) {
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            int current = (user.getReputation() == null) ? 0 : user.getReputation();
            user.setReputation(current + change);
            userMapper.updateById(user);
            System.out.println("【声望变动】用户 " + user.getNickname() + " " + reason + " -> " + user.getReputation());
        }
    }

    /**
     * 逆地理编码 (坐标 -> 地址名称)
     * 供前端地图选点使用，解决 CORS 和 User-Agent 问题
     */
    public String reverseGeocode(BigDecimal lat, BigDecimal lon) {
        try {
            // 构造 URL
            String url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + lat + "&lon=" + lon + "&zoom=18&addressdetails=1";

            // 设置 Header (关键！防止 403)
            HttpHeaders headers = new HttpHeaders();
            headers.add("User-Agent", "Jienoshiri-Student-Project/1.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                // 获取显示名称
                return root.get("display_name").asText();
            }
        } catch (Exception e) {
            System.err.println(">>> [OSM] 逆解析失败: " + e.getMessage());
        }
        return "未知地点";
    }

    // 刷新配置的方法 (Public供Controller调用)
    public void refreshWeights() {
        List<com.jienoshiri.platform.entity.SysConfig> list = sysConfigMapper.selectList(null);
        for (com.jienoshiri.platform.entity.SysConfig config : list) {
            try {
                weightCache.put(config.getParamKey(), Double.parseDouble(config.getParamValue()));
            } catch (Exception e) {}
        }
        System.out.println(">>> 推荐系统权重已更新: " + weightCache);
    }

    // 辅助方法：获取权重，取不到则用默认值
    private double getWeight(String key, double defaultValue) {
        return weightCache.getOrDefault(key, defaultValue);
    }
}
