package com.runisys.orbit.spacevisibility.application.ballistic.contract;

import java.util.List;
import java.util.Map;

/** 弹道文件用例输入输出契约；HTTP与存储适配器复用字段，计算结果不增加文件包装层。 */
public final class BallisticFileModels {

    private BallisticFileModels() {
    }

    /** 不透明登记引用；serverPath 可直接放入旧 csvPath 等路径字段。 */
    public static class FileReference {
        /** 随机 UUID，只作登记表查询键，不能据此拼接文件路径。 */
        public String fileId;
        /** 客户端原文件名的安全末段，保留 Unicode。 */
        public String originalName;
        /** 实际已存储字节数。 */
        public long sizeBytes;
        /** 经 HTTP 媒体类型校验后的下载类型。 */
        public String contentType;
        /** 服务端规范绝对路径，与浏览器本机路径无关。 */
        public String serverPath;
    }

    /** 结果登记请求，csvPath 必填；任务和目标标识仅用于登记关联。 */
    public static class RegisterResultRequest {
        public String csvPath;
        public String taskName;
        public String targetId;
    }

    /** 原表头与单元格文本预览；未知单位/坐标不推测，不返回解析后的数值替代原单元格。 */
    public static class Preview {
        public String fileId;
        public List<String> header;
        public List<List<String>> rows;
        public long totalRows;
        /** 页码从 1 开始，limit 默认 50，上限由配置确定（默认 500）。 */
        public int page;
        public int limit;
        /** 仅在识别完整已知表头时给出时间、位置、速度和坐标信息。 */
        public Map<String, String> metadata;
    }
}
