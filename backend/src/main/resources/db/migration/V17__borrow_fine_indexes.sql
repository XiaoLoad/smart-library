-- ===== V17：借阅表性能索引补齐 =====
-- 定时状态同步与逾期拦截查询按 due_time / pickup_deadline 过滤，
-- 数据量增长后无索引会导致全表扫描。覆盖索引（含 user_id）让
-- selectOverduePending / countByUserAndStatuses 走索引而不回表。

-- 逾期拦截/续借校验：user_id + status 复合（selectOverduePending、同书查重、额度计数）
CREATE INDEX idx_borrow_user_status ON borrows (user_id, status);

-- 定时任务与到期扫描：借阅中状态按应还时间过滤
CREATE INDEX idx_borrow_due_time ON borrows (due_time);

-- 待取书超期取消：待取书状态按取书截止时间过滤
CREATE INDEX idx_borrow_pickup_deadline ON borrows (pickup_deadline);

-- 罚款缴纳/未缴统计：user_id + status 复合
CREATE INDEX idx_fine_user_status ON fines (user_id, status);
