package com.rj.model.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("work_order_attachment")
@Schema(description = "工单附件实体")
public class WorkOrderAttachment {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属工单ID")
    private Long orderId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "存储 key(OSS/MinIO 对象 key)")
    private String filePath;

    @Schema(description = "文件大小(字节)")
    private Long fileSize;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "上传人ID")
    private Long uploaderId;

    @Schema(description = "上传时间")
    private LocalDateTime createTime;
}
