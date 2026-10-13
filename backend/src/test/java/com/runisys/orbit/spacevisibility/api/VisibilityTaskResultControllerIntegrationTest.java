package com.runisys.orbit.spacevisibility.api;

import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.VisibilityTaskResultEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.VisibilityTaskResultMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/** 验证异步可见性规划任务结果记录的 CRUD 接口（H2 环境）。 */
@SpringBootTest
@AutoConfigureMockMvc
class VisibilityTaskResultControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private VisibilityTaskResultMapper taskResultMapper;

    @BeforeEach
    void resetTaskResults() {
        taskResultMapper.delete(null);
    }

    /** 验证分页查询默认参数、倒序排序以及列表项不携带请求与结果 JSON 大字段。 */
    @Test
    void pagesTaskResultRecords() throws Exception {
        insertRecord("task-page-1", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 1, 0, 0));
        insertRecord("task-page-2", "FAILED", null, LocalDateTime.of(2026, 8, 2, 0, 0));

        mockMvc.perform(get("/api/v1/space/visibility/task-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pageNumber").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.records[0].taskId").value("task-page-2"))
                .andExpect(jsonPath("$.records[1].taskId").value("task-page-1"))
                .andExpect(jsonPath("$.records[0].requestJson").doesNotExist())
                .andExpect(jsonPath("$.records[0].resultJson").doesNotExist());
    }

    /** 验证状态与关键字筛选参数。 */
    @Test
    void filtersTaskResultRecords() throws Exception {
        insertRecord("task-filter-ok", "SUCCEEDED", "重点目标", LocalDateTime.of(2026, 8, 1, 0, 0));
        insertRecord("task-filter-bad", "FAILED", null, LocalDateTime.of(2026, 8, 2, 0, 0));

        mockMvc.perform(get("/api/v1/space/visibility/task-results")
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].taskId").value("task-filter-bad"));

        mockMvc.perform(get("/api/v1/space/visibility/task-results")
                        .param("keyword", "重点"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].taskId").value("task-filter-ok"));

        mockMvc.perform(get("/api/v1/space/visibility/task-results")
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
    }

    /** 验证单条记录明细包含请求与结果 JSON 原文，不存在时返回 404。 */
    @Test
    void readsTaskResultDetail() throws Exception {
        Long id = insertRecord("task-detail", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 1, 0, 0));

        mockMvc.perform(get("/api/v1/space/visibility/task-results/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(id)))
                .andExpect(jsonPath("$.taskId").value("task-detail"))
                .andExpect(jsonPath("$.taskType").value("VISIBILITY"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requestJson").value("{\"observerSatelliteIds\":[1]}"))
                .andExpect(jsonPath("$.resultJson").value("{\"results\":[]}"))
                .andExpect(jsonPath("$.createTime").isString());

        mockMvc.perform(get("/api/v1/space/visibility/task-results/{id}", 999999L))
                .andExpect(status().isNotFound());
    }

    /**
     * 雪花算法生成的任务记录主键通常超过 JavaScript Number 的安全整数上限。
     *
     * <p>接口必须将此类主键作为 JSON 字符串返回，否则浏览器在读取列表时会发生舍入，
     * 再用被舍入的值查询明细便会错误地得到 404。本用例直接校验响应原文中的双引号，
     * 防止 JSONPath 对数值的隐式转换掩盖这一跨端精度问题。</p>
     */
    @Test
    void serializesUnsafeTaskResultIdAsString() throws Exception {
        Long unsafeId = 2097222553115705346L;
        insertRecordWithId(unsafeId, "task-unsafe-id", "SUCCEEDED", null,
                LocalDateTime.of(2026, 8, 3, 0, 0));

        mockMvc.perform(get("/api/v1/space/visibility/task-results"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"id\":\"" + unsafeId + "\"")));

        mockMvc.perform(get("/api/v1/space/visibility/task-results/{id}", unsafeId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"id\":\"" + unsafeId + "\"")))
                .andExpect(jsonPath("$.taskId").value("task-unsafe-id"));
    }

    /** 验证备注更新返回更新后明细，记录不存在时返回 404。 */
    @Test
    void updatesTaskResultRemark() throws Exception {
        Long id = insertRecord("task-remark", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 1, 0, 0));

        mockMvc.perform(put("/api/v1/space/visibility/task-results/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"已核对\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remark").value("已核对"))
                .andExpect(jsonPath("$.requestJson").isString());

        mockMvc.perform(put("/api/v1/space/visibility/task-results/{id}", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    /** 验证删除记录后明细不可再读取，重复删除返回 404。 */
    @Test
    void deletesTaskResultRecord() throws Exception {
        Long id = insertRecord("task-delete", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 1, 0, 0));

        mockMvc.perform(delete("/api/v1/space/visibility/task-results/{id}", id))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/space/visibility/task-results/{id}", id))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/space/visibility/task-results/{id}", id))
                .andExpect(status().isNotFound());
    }

    private Long insertRecord(String taskId, String status, String remark, LocalDateTime createTime) {
        return insertRecordWithId(null, taskId, status, remark, createTime);
    }

    /** 使用指定主键写入测试记录；传入 null 时由 MyBatis-Plus 雪花算法生成主键。 */
    private Long insertRecordWithId(Long id, String taskId, String status, String remark, LocalDateTime createTime) {
        VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
        entity.setId(id);
        entity.setTaskId(taskId);
        entity.setTaskType("VISIBILITY");
        entity.setStatus(status);
        entity.setTotalPairs(1L);
        entity.setCompletedPairs(1L);
        entity.setRequestJson("{\"observerSatelliteIds\":[1]}");
        entity.setResultJson("{\"results\":[]}");
        entity.setRemark(remark);
        entity.setCreateTime(createTime);
        entity.setCompleteTime(createTime.plusMinutes(1));
        taskResultMapper.insert(entity);
        return entity.getId();
    }
}
