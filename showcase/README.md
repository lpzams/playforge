# 作品展

`site/` 是发布到 GitHub Pages 的静态站点（`.github/workflows/pages.yml` 自动部署）。

更新内容：启动后端（任意 profile），然后

```bash
node showcase/export.mjs     # 导出所有"已通过试玩"的游戏及其完整生成记录到 site/
```

导出后检查 `site/data/games.json` 再提交。页面把数据里的所有文本（包括 AI 输出）都当纯文本渲染。
