-- WinTool 的 SPACE 载荷中，angleRate=0 或 -1 表示“不限制跟踪角速度”，不是零角速度。
-- 同时保留 startupAngleRate=-1 这一“禁用”标记，供前端按原始文档参数回显。
-- 仅更新三颗交付基线观测星的 SPACE profile；GROUND profile 保持原有下传参数不变。
UPDATE ZG_XZ_MANAGE
SET PAYLOAD_JSON_ = REPLACE(CAST(PAYLOAD_JSON_ AS CHAR), '"angleRate": 0.3', '"angleRate": 0')
WHERE ID_ = 2068
  AND PAYLOAD_JSON_ IS NOT NULL;

UPDATE ZG_XZ_MANAGE
SET PAYLOAD_JSON_ = REPLACE(
        REPLACE(CAST(PAYLOAD_JSON_ AS CHAR), '"angleRate": 0.3', '"angleRate": 0'),
        '"startupAngleRate": 0.16', '"startupAngleRate": -1')
WHERE ID_ = 2067
  AND PAYLOAD_JSON_ IS NOT NULL;

UPDATE ZG_XZ_MANAGE
SET PAYLOAD_JSON_ = REPLACE(
        REPLACE(CAST(PAYLOAD_JSON_ AS CHAR), '"angleRate": 0.3', '"angleRate": -1'),
        '"startupAngleRate": 0.1', '"startupAngleRate": -1')
WHERE ID_ = 2374
  AND PAYLOAD_JSON_ IS NOT NULL;
