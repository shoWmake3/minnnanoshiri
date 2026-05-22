<template>
  <view class="page-container">
    
    <div class="ambient-bg">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
      <div class="noise-overlay"></div>
    </div>

    <view class="header-section">
      <view class="header-title-row">
        <text class="page-title">知识百科</text>
        <text class="page-sub">Knowledge Base</text>
      </view>
      
      <view class="search-box">
        <text class="search-icon">🔍</text>
        <input class="search-input" v-model="keyword" placeholder="搜索 签证 / 租房 / 攻略..." 
          @confirm="doSearch" placeholder-style="color:#94a3b8" />
        <view class="search-btn" @click="doSearch">→</view>
      </view>
    </view>

    <scroll-view scroll-y class="scroll-body" show-scrollbar="true">
      <view class="content-wrapper">

        <view class="wiki-list">
          <view class="list-header">
            <text class="section-label">{{ keyword ? '搜索结果' : '最新收录' }}</text>
            <text class="count-badge" v-if="list.length">{{ list.length }} 条</text>
          </view>

          <view class="wiki-card" v-for="(item, index) in list" :key="index" @click="goToDetail(item)"
            :style="{ animationDelay: index * 0.05 + 's' }">
            
            <view class="wiki-main">
              <view class="wiki-title-row">
                <text class="wiki-icon">📑</text>
                <text class="wiki-title">{{ item.title }}</text>
              </view>
              <text class="wiki-summary">{{ item.summary || item.content.substring(0, 50) + '...' }}</text>
            </view>

            <view class="wiki-footer">
              <view class="tag-pill">
                <text># {{ item.category || '综合' }}</text>
              </view>
              <view class="meta-right">
                <text class="meta-item">👁️ {{ item.viewCount || 0 }}</text>
                <text class="arrow-icon">›</text>
              </view>
            </view>
          </view>

          <view v-if="list.length === 0" class="empty-state">
            <view class="empty-icon">📚</view>
            <text>知识库正在建设中...</text>
          </view>
        </view>

      </view>
      
      <view style="height: 60px;"></view>
    </scroll-view>
  </view>
</template>

<script setup>
import { ref } from 'vue';
import { onShow } from '@dcloudio/uni-app';

const keyword = ref('');
const list = ref([]);

// 带图标的分类配置
const categories = [
  { name: '签证办理', emoji: '🛂' },
  { name: '租房攻略', emoji: '🏠' },
  { name: '交通出行', emoji: '🚇' },
  { name: '学术研究', emoji: '🎓' },
  { name: '打工兼职', emoji: '💼' }
];

onShow(() => {
  fetchList();
});

const fetchList = () => {
    const url = keyword.value 
        ? `http://localhost:8080/wiki/search?keyword=${keyword.value}`
        : `http://localhost:8080/wiki/list`;
        
    uni.request({
        url: url,
        success: (res) => { 
            list.value = res.data; 
        }
    });
};

const doSearch = () => { fetchList(); };
const filterByCat = (name) => { keyword.value = name; fetchList(); };

const goToDetail = (item) => {
  // 将整个 item 存入缓存，方便详情页读取（避免 URL 传参过长）
  uni.setStorageSync('currentWiki', item);
  uni.navigateTo({
    url: '/pages/wiki/wiki-detail'
  });
};
</script>

<style>
:root {
  --primary: #6366f1;
  --bg-page: #f8fafc;
  --text-main: #1e293b;
  --text-sub: #64748b;
}

.page-container {
  height: 100vh;
  display: flex; flex-direction: column;
  position: relative; overflow: hidden;
  background: var(--bg-page);
}

/* 1. 动态背景 */
.ambient-bg { position: absolute; top: 0; left: 0; width: 100%; height: 100%; z-index: 0; pointer-events: none; }
.noise-overlay { position: absolute; top: 0; left: 0; width: 100%; height: 100%; opacity: 0.03; background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E"); }
.blob { position: absolute; filter: blur(80px); opacity: 0.6; animation: float 10s infinite alternate; }
.blob-1 { top: -10%; left: -10%; width: 60vw; height: 60vw; background: #c4b5fd; }
.blob-2 { bottom: -10%; right: -10%; width: 60vw; height: 60vw; background: #a5f3fc; animation-delay: -2s; }
.blob-3 { top: 40%; left: 30%; width: 40vw; height: 40vw; background: #fecaca; opacity: 0.4; animation-delay: -4s; }
@keyframes float { 0% { transform: translate(0, 0); } 100% { transform: translate(30px, 40px); } }

/* 2. 头部区域 */
.header-section {
  position: relative; z-index: 10;
  padding: 50px 20px 20px;
  background: rgba(255,255,255,0.1);
  backdrop-filter: blur(10px);
}

.header-title-row { margin-bottom: 15px; }
.page-title { font-size: 24px; font-weight: 800; color: var(--text-main); margin-right: 8px; }
.page-sub { font-size: 14px; color: var(--text-sub); font-family: monospace; opacity: 0.8; }

.search-box {
  display: flex; align-items: center;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(255,255,255,0.8);
  border-radius: 16px; padding: 0 16px; height: 48px;
  box-shadow: 0 4px 15px rgba(0,0,0,0.05);
  transition: all 0.3s;
}
.search-box:focus-within { background: #fff; box-shadow: 0 8px 25px rgba(99, 102, 241, 0.15); transform: translateY(-2px); }
.search-icon { font-size: 18px; margin-right: 10px; opacity: 0.6; }
.search-input { flex: 1; height: 100%; font-size: 15px; color: var(--text-main); }
.search-btn { font-size: 18px; color: var(--primary); font-weight: bold; padding: 5px; }

/* 3. 滚动主体 */
.scroll-body { flex: 1; height: 0; position: relative; z-index: 1; }
.content-wrapper { padding: 20px; }

/* 分类区域 */
.category-section { margin-bottom: 30px; }
.section-label { font-size: 14px; font-weight: 700; color: #475569; margin-bottom: 12px; display: block; padding-left: 4px; border-left: 3px solid var(--primary); line-height: 1; }

.cat-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.cat-card {
  height: 80px; border-radius: 16px; padding: 15px;
  position: relative; overflow: hidden;
  display: flex; flex-direction: column; justify-content: center;
  box-shadow: 0 4px 10px rgba(0,0,0,0.03);
  border: 1px solid rgba(255,255,255,0.5);
  transition: transform 0.2s;
  cursor: pointer;
}
.cat-card:active { transform: scale(0.98); }

/* 分类配色 */
.cat-color-0 { background: linear-gradient(135deg, #e0f2fe 0%, #bae6fd 100%); }
.cat-color-1 { background: linear-gradient(135deg, #fce7f3 0%, #fbcfe8 100%); }
.cat-color-2 { background: linear-gradient(135deg, #dcfce7 0%, #bbf7d0 100%); }
.cat-color-3 { background: linear-gradient(135deg, #ede9fe 0%, #ddd6fe 100%); }
.cat-color-4 { background: linear-gradient(135deg, #ffedd5 0%, #fed7aa 100%); grid-column: span 2; } /* 最后一个跨两列 */

.cat-emoji { font-size: 24px; margin-bottom: 4px; z-index: 2; }
.cat-name { font-size: 14px; font-weight: 700; color: #334155; z-index: 2; }
.cat-bg-icon { position: absolute; right: -10px; bottom: -10px; font-size: 60px; opacity: 0.15; transform: rotate(-20deg); z-index: 1; pointer-events: none; }

/* 词条列表 */
.list-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.count-badge { font-size: 12px; color: #94a3b8; background: rgba(255,255,255,0.5); padding: 2px 8px; border-radius: 10px; }

.wiki-card {
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(10px);
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid rgba(255,255,255,0.6);
  box-shadow: 0 4px 15px -5px rgba(0,0,0,0.05);
  animation: slideUp 0.5s ease-out backwards;
  transition: background 0.2s;
}
.wiki-card:active { background: rgba(255,255,255,0.9); }

@keyframes slideUp { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }

.wiki-title-row { display: flex; align-items: center; margin-bottom: 8px; }
.wiki-icon { margin-right: 8px; font-size: 16px; }
.wiki-title { font-size: 16px; font-weight: 700; color: var(--text-main); }
.wiki-summary { 
  font-size: 13px; 
  color: var(--text-sub); 
  line-height: 1.6; 
  margin-bottom: 12px; 
  display: -webkit-box; 
  -webkit-line-clamp: 2; 
  line-clamp: 2; 
  -webkit-box-orient: vertical; 
  overflow: hidden; 
}

.wiki-footer { display: flex; justify-content: space-between; align-items: center; border-top: 1px dashed rgba(0,0,0,0.05); padding-top: 8px; }
.tag-pill { font-size: 11px; color: var(--primary); background: rgba(99, 102, 241, 0.08); padding: 2px 8px; border-radius: 6px; font-weight: 600; }
.meta-right { display: flex; align-items: center; gap: 8px; }
.meta-item { font-size: 11px; color: #94a3b8; }
.arrow-icon { font-size: 16px; color: #cbd5e1; }

.empty-state { text-align: center; padding-top: 60px; opacity: 0.6; }
.empty-icon { font-size: 48px; margin-bottom: 10px; }

/* PC 适配 */
@media screen and (min-width: 800px) {
  /* 1. 容器重置 */
  .page-container {
    overflow-y: auto; /* PC端页面滚动 */
    display: block;   /* 取消 flex */
  }

  /* 2. 头部加宽 */
  .header-section { padding: 40px 60px; display: flex; align-items: center; justify-content: space-between; }
  .header-title-row { margin-bottom: 0; }
  .search-box { width: 400px; }

  /* 3. 滚动区域变容器 */
  .scroll-body {
    height: auto;
    overflow: visible;
    width: 100%;
    display: block; /* 取消 flex */
    padding-bottom: 100px;
  }

  /* 4. 内容居中 */
  .content-wrapper {
    width: 800px; margin: 0 auto;
    background: rgba(255,255,255,0.5);
    border-radius: 24px; padding: 40px;
    box-shadow: 0 20px 60px -10px rgba(0,0,0,0.05);
  }

  /* 5. 分类变为 5 列 */
  .cat-grid { grid-template-columns: repeat(5, 1fr); }
  .cat-color-4 { grid-column: span 1; }

  /* 6. 卡片悬停 */
  .cat-card:hover { transform: translateY(-5px); box-shadow: 0 10px 20px rgba(0,0,0,0.1); }
  .wiki-card { cursor: pointer; transition: transform 0.2s; }
  .wiki-card:hover { transform: scale(1.01); box-shadow: 0 10px 30px rgba(0,0,0,0.08); background: #fff; }
}
</style>