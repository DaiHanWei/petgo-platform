-- 专题线 video Story 1.3 code review：同一个视频对象只能属于一条帖子。
--
-- 对账器（1.4）按「帖子」改视频 / 封面对象的 ACL。若两条帖子共用同一个视频 key，
-- 删掉其中一条会把另一条（仍公开）的视频改成私有，而后者 desired=applied 不会再被对账 → 永久 403。
-- 发帖时事务内先查一次（友好报错），本唯一索引兜并发。存量没有视频帖（1.1–1.4 未部署），可直接建。
CREATE UNIQUE INDEX uq_content_posts_video_object_key
    ON content_posts (video_object_key)
    WHERE video_object_key IS NOT NULL;
