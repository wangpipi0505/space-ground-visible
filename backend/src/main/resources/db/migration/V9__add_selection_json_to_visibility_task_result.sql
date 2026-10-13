-- 目标观测“确认选择”:用户在结果列表勾选目标后提交的确认明细原文。
-- 以最后一次提交为准;历史记录该列为 NULL,表示未做过确认选择。
ALTER TABLE ZG_VISIBILITY_TASK_RESULT
    ADD COLUMN SELECTION_JSON_ LONGTEXT NULL;
