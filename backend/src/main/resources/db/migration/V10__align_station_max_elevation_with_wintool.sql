-- V10: 喀什/牡丹江站点最大仰角 87° -> 90°,与 WinTool 对齐口径一致(2026-09 全量比对 V8 结论)。
-- 近顶过境时 87° 会截断过顶段(如 2026-09-02 B-->牡丹江弧段止端差 19.8s),90° 下双方逐秒吻合。
UPDATE ZG_STATION
SET MAX_ELEVATION_ = 90.0
WHERE ID_ IN ('ST-CN-001', 'ST-CN-010')
  AND MAX_ELEVATION_ = 87.0;
