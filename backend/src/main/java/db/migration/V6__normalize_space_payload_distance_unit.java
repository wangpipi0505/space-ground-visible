package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 将交付版中按公里填写、却未记录单位的对空 {@code distance} 历史值迁移为领域层标准米。
 *
 * <p>使用 Java 迁移而不是数据库 JSON 函数，以同时支持 MySQL 和集成环境预留的达梦数据库，
 * 也避免 JSON 列被 JDBC 驱动返回为文本 JSON 时出现数据库方言差异。迁移幂等：只有距离单位
 * 缺失或为 {@code KM} 的 SPACE 载荷会被换算并标记为 {@code M}。</p>
 */
public class V6__normalize_space_payload_distance_unit extends BaseJavaMigration {

    private static final double METRES_PER_KILOMETRE = 1_000.0;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement query = context.getConnection().createStatement();
             ResultSet rows = query.executeQuery("SELECT ID_, PAYLOAD_JSON_ FROM ZG_XZ_MANAGE")) {
            while (rows.next()) {
                long satelliteId = rows.getLong("ID_");
                String rawPayload = rows.getString("PAYLOAD_JSON_");
                String migratedPayload = migratePayload(rawPayload);
                if (migratedPayload == null) {
                    continue;
                }
                try (PreparedStatement update = context.getConnection().prepareStatement(
                        "UPDATE ZG_XZ_MANAGE SET PAYLOAD_JSON_ = ? WHERE ID_ = ?")) {
                    update.setString(1, migratedPayload);
                    update.setLong(2, satelliteId);
                    update.executeUpdate();
                }
            }
        }
    }

    /**
     * 识别 JSON 对象和部分数据库驱动返回的 JSON 字符串，返回 {@code null} 表示该行无需改写。
     * 无单位的历史 distance 与显式 KM 均按公里处理；显式 M 数据原样保留，确保多次部署安全。
     */
    private String migratePayload(String rawPayload) throws Exception {
        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(rawPayload);
        JsonNode payload = root.isTextual() ? objectMapper.readTree(root.asText()) : root;
        if (!(payload instanceof ObjectNode) || !"SPACE".equalsIgnoreCase(payload.path("payloadType").asText())) {
            return null;
        }
        String unit = payload.path("distanceUnit").asText("");
        if ("M".equalsIgnoreCase(unit)) {
            return null;
        }
        JsonNode distance = payload.get("distance");
        if (!(distance instanceof ArrayNode) || distance.size() != 2 || !distance.get(0).isNumber() || !distance.get(1).isNumber()) {
            // 格式无效的配置继续交给应用领域校验报出明确错误，迁移不能臆造数值。
            return null;
        }
        ArrayNode values = (ArrayNode) distance;
        values.set(0, objectMapper.getNodeFactory().numberNode(values.get(0).asDouble() * METRES_PER_KILOMETRE));
        values.set(1, objectMapper.getNodeFactory().numberNode(values.get(1).asDouble() * METRES_PER_KILOMETRE));
        ((ObjectNode) payload).put("distanceUnit", "M");
        return objectMapper.writeValueAsString(payload);
    }
}
