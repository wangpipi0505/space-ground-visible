-- 保存异步目标观测任务的完整跳过组合清单。RESULT_JSON_ 仅保留接口响应中最多 500 条的展示明细；
-- 此列不截断，供任务明细和故障定位使用。
ALTER TABLE ZG_VISIBILITY_TASK_RESULT ADD COLUMN SKIPPED_PAIRS_ LONGTEXT;
